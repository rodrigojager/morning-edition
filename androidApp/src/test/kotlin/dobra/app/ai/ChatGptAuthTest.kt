package dobra.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.KeyGenerator

/** Local callback plus synthetic OAuth replies. No OpenAI service, account or real credential is used. */
@RunWith(AndroidJUnit4::class)
class ChatGptAuthTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = pair.public as RSAPublicKey
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private val jwks: JSONObject get() = JSONObject().put("keys", JSONArray().put(JSONObject()
        .put("kid", "test-key").put("kty", "RSA").put("alg", "RS256").put("use", "sig")
        .put("n", encode(publicKey.modulus.toByteArray())).put("e", encode(publicKey.publicExponent.toByteArray()))))
    private val clock = 1_800_000_000_000L
    private fun claims(nonce: String) = JSONObject().put("iss", ChatGptAuth.AUTH).put("aud", "issued-test-client")
        .put("sub", "synthetic-subject").put("email", "reader@example.test").put("exp", clock / 1000 + 3600).put("nonce", nonce)
    private fun jwt(claims: JSONObject): String {
        val head = encode("""{"alg":"RS256","kid":"test-key"}""".toByteArray())
        val body = encode(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update("$head.$body".toByteArray()); sign()
        }
        return "$head.$body.${encode(signature)}"
    }

    @Test
    fun identityRejectsWrongNonceAudienceIssuerExpiryAndSignature() {
        val valid = claims("expected")
        assertEquals("synthetic-subject", ChatGptAuth.validateIdentity(jwt(valid), jwks, "issued-test-client", "expected", clock).getString("sub"))
        for ((field, value) in listOf("nonce" to "wrong", "aud" to "other", "iss" to "https://example.test", "exp" to clock / 1000)) {
            val changed = JSONObject(valid.toString()).put(field, value)
            assertThrows(IllegalArgumentException::class.java) {
                ChatGptAuth.validateIdentity(jwt(changed), jwks, "issued-test-client", "expected", clock)
            }
        }
        val token = jwt(valid)
        val parts = token.split('.')
        val broken = "${parts[0]}.${parts[1]}.${encode(ByteArray(256))}"
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptAuth.validateIdentity(broken, jwks, "issued-test-client", "expected", clock)
        }
    }

    @Test
    fun queryRejectsDuplicateState() {
        assertThrows(IllegalArgumentException::class.java) { ChatGptAuth.query("state=one&state=two") }
        assertEquals("a+b c", ChatGptAuth.query(ChatGptAuth.form(mapOf("code" to "a+b c")))["code"])
    }

    @Test
    fun callbackEncryptsSessionRefreshesOnceAndRevokesWithoutLosingApiKeys(): Unit = runBlocking {
        val storage = MemoryBlobStorage()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmCipher { key }
        val registry = ProviderRegistry(MemoryBlobStorage(), cipher)
        val existing = registry.add("Existing", ProviderKind.ANTHROPIC, "https://example.test", "synthetic-api-key")
        val opened = CompletableDeferred<String>()
        val sent = mutableListOf<HttpRequest>()
        var nonce = ""
        var time = clock
        val transport = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                sent += request
                val body = when {
                    request.url.endsWith("jwks.json") -> jwks.toString()
                    request.url.endsWith("/revoke") -> ""
                    ChatGptAuth.query(request.body.orEmpty())["grant_type"] == "refresh_token" ->
                        """{"access_token":"rotated-synthetic-access","refresh_token":"rotated-synthetic-refresh","expires_in":3600,"token_type":"Bearer"}"""
                    else -> JSONObject().put("access_token", "synthetic-access").put("refresh_token", "synthetic-refresh")
                        .put("expires_in", 120).put("token_type", "Bearer").put("scope", "chatgpt.tokens.use.direct")
                        .put("id_token", jwt(claims(nonce))).toString()
                }
                return HttpResponse(200, emptyMap(), body)
            }
        }
        val auth = ChatGptAuth(storage, cipher, transport, registry, { time }, Dispatchers.Unconfined)
        auth.start { opened.complete(it) }
        val address = withTimeout(5000) { opened.await() }
        val params = ChatGptAuth.query(URI(address).rawQuery)
        assertEquals("dynamic_agent_client", params["client_id"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("Primeira Dobra", params["agent_name_hint"])
        nonce = params.getValue("nonce")
        val redirect = URI(params.getValue("redirect_uri"))
        withContext(Dispatchers.IO) {
            Socket(redirect.host, redirect.port).use { peer ->
                peer.soTimeout = 5000
                val query = ChatGptAuth.form(mapOf("state" to params.getValue("state"), "code" to "synthetic-code", "client_id" to "issued-test-client"))
                peer.getOutputStream().write("GET ${redirect.path}?$query HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                assertTrue(peer.getInputStream().bufferedReader().readText().startsWith("HTTP/1.1 200"))
            }
        }
        withTimeout(5000) { auth.status.first { !it.busy } }
        assertFalse(auth.status.value.error)
        assertEquals("reader@example.test", auth.status.value.account)
        assertTrue(registry.state.value.providers.any { it.kind == ProviderKind.CHATGPT_OAUTH })
        assertFalse(storage.blobs.values.any { "synthetic-access" in it || "synthetic-refresh" in it })
        assertEquals("synthetic-access", auth.accessToken())
        val exchange = ChatGptAuth.query(sent.first().body!!)
        assertEquals("issued-test-client", exchange["client_id"])
        assertEquals(redirect.toString(), exchange["redirect_uri"])
        time += 121_000
        val refreshed = coroutineScope { List(3) { async(Dispatchers.IO) { auth.accessToken() } }.awaitAll() }
        assertEquals(List(3) { "rotated-synthetic-access" }, refreshed)
        assertEquals(1, sent.count { it.body?.contains("grant_type=refresh_token") == true })
        auth.disconnect()
        assertEquals(existing, registry.state.value.providers.single())
        assertEquals("rotated-synthetic-refresh", ChatGptAuth.query(sent.last().body!!)["token"])
        val retained = JSONObject(String(cipher.decrypt(Base64.getDecoder().decode(storage.blobs.getValue("chatgpt-session.enc")))))
        assertEquals("issued-test-client", retained.getString("client_id"))
        assertFalse(retained.has("access_token"))
        assertFalse(retained.has("refresh_token"))
        assertFalse(auth.status.value.revocationUnconfirmed)
        assertThrows(AiException.NotConfigured::class.java) { runBlocking { auth.accessToken() } }
        val returning = CompletableDeferred<String>()
        auth.start { returning.complete(it) }
        val returningParams = ChatGptAuth.query(URI(withTimeout(5000) { returning.await() }).rawQuery)
        assertEquals("issued-test-client", returningParams["client_id"])
        assertEquals(params["ext_agent_host_id"], returningParams["ext_agent_host_id"])
        assertFalse(returningParams.containsKey("agent_name_hint"))
        assertFalse(returningParams.containsKey("id_token_hint"))
        auth.disconnect()
    }

    @Test
    fun cancellingLoginClosesCallbackWithoutAddingProvider(): Unit = runBlocking {
        val storage = MemoryBlobStorage()
        val cipher = AesGcmCipher { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val registry = ProviderRegistry(storage, cipher)
        val opened = CompletableDeferred<String>()
        val auth = ChatGptAuth(storage, cipher, object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse = error("cancelled login must not exchange")
        }, registry, { clock }, Dispatchers.Unconfined)
        auth.start { opened.complete(it) }
        withTimeout(5000) { opened.await() }
        auth.disconnect()
        assertTrue(registry.state.value.providers.isEmpty())
        assertFalse(auth.status.value.busy)
        assertFalse(storage.blobs.containsKey("chatgpt-session.enc"))
    }

    @Test
    fun failedRemoteRevocationClearsTokensAndShowsWarning(): Unit = runBlocking {
        val storage = MemoryBlobStorage()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmCipher { key }
        val registry = ProviderRegistry(MemoryBlobStorage(), cipher)
        registry.addChatGpt()
        val session = JSONObject().put("client_id", "issued-test-client").put("subject", "synthetic-subject")
            .put("email", "reader@example.test").put("refresh_token", "synthetic-refresh").put("access_token", "synthetic-access")
        storage.write("chatgpt-session.enc", Base64.getEncoder().encodeToString(cipher.encrypt(session.toString().toByteArray())))
        val auth = ChatGptAuth(storage, cipher, object : HttpTransport {
            override suspend fun send(request: HttpRequest) = HttpResponse(503, emptyMap(), "")
        }, registry)
        auth.disconnect()
        assertTrue(auth.status.value.revocationUnconfirmed)
        assertTrue(registry.state.value.providers.isEmpty())
        assertThrows(AiException.NotConfigured::class.java) { runBlocking { auth.accessToken() } }
    }
}
