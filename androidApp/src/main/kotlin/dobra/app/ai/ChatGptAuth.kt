package dobra.app.ai

import dobra.ai.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/** Local public OAuth client. Tokens are sealed together; no browser cookies or API keys are imported. */
class ChatGptAuth(
    private val storage: BlobStorage,
    private val cipher: SecretCipher,
    private val transport: HttpTransport,
    private val registry: ProviderRegistry,
    private val now: () -> Long = System::currentTimeMillis,
    private val browserDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val callbackReceived: String = "Login received. Return to Primeira Dobra to check the connection.",
    private val callbackRefused: String = "Request refused.",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<Status> = _status
    private var login: Job? = null
    @Volatile private var listener: ServerSocket? = null
    data class Status(val busy: Boolean = false, val error: Boolean = false,
        val account: String? = null, val revocationUnconfirmed: Boolean = false)
    private fun currentStatus(busy: Boolean = false, error: Boolean = false) = Status(busy, error,
        runCatching { readSession()?.takeIf { it.has("access_token") }?.optString("email")?.takeIf { it.isNotBlank() } }.getOrNull())
    private val host: String = storage.read("chatgpt-host").takeUnless { it.isNullOrBlank() }
        ?: "urn:uuid:${UUID.randomUUID()}".also { storage.write("chatgpt-host", it) }

    fun start(openBrowser: (String) -> Unit) {
        if (login?.isActive == true) return
        _status.value = currentStatus(busy = true)
        login = scope.launch {
            try {
                withTimeout(5 * 60_000L) {
                    val old = lock.withLock { readSession() }
                    val state = random()
                    val nonce = random()
                    val verifier = random()
                    val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                    listener = socket
                    socket.use {
                        socket.soTimeout = 1000
                        val redirect = "http://127.0.0.1:${socket.localPort}/auth/callback"
                        val client = old?.optString("client_id")?.takeIf { it.isNotBlank() }
                        val params = linkedMapOf(
                            "client_id" to (client ?: "dynamic_agent_client"), "ext_agent_host_id" to host,
                            "response_type" to "code", "redirect_uri" to redirect,
                            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                            "resource" to RESOURCE, "state" to state, "nonce" to nonce,
                            "code_challenge_method" to "S256",
                            "code_challenge" to encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())),
                        )
                        if (client == null) params["agent_name_hint"] = "Primeira Dobra"
                        else old.optString("id_token").takeIf { it.isNotBlank() }?.let { params["id_token_hint"] = it }
                        withContext(browserDispatcher) { openBrowser("$AUTH/api/accounts/authorize?${form(params)}") }
                        val callback = awaitCallback(socket, state)
                        val issued = callback["client_id"] ?: client ?: error("missing client")
                        require(issued != "dynamic_agent_client" && (client == null || client == issued))
                        require(callback["error"] == null)
                        val code = callback["code"]?.takeIf { it.isNotBlank() } ?: error("missing code")
                        val tokens = exchange(mapOf("grant_type" to "authorization_code", "client_id" to issued,
                            "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE))
                        val jwks = transport.send(HttpRequest("GET", "$AUTH/.well-known/jwks.json", emptyMap()))
                        require(jwks.status == 200)
                        val identity = validateIdentity(tokens.getString("id_token"), JSONObject(jwks.body), issued, nonce, now())
                        if (old != null) require(identity.getString("sub") == old.getString("subject"))
                        tokens.put("client_id", issued).put("subject", identity.getString("sub"))
                            .put("email", identity.optString("email")).put("saved_at", now())
                        validateTokens(tokens)
                        lock.withLock {
                            currentCoroutineContext().ensureActive()
                            saveSession(tokens)
                            registry.addChatGpt()
                        }
                    }
                }
                _status.value = currentStatus()
            } catch (_: TimeoutCancellationException) {
                _status.value = currentStatus(error = true)
            } catch (e: CancellationException) {
                _status.value = currentStatus()
                throw e
            } catch (_: Exception) {
                // Never publish token endpoint bodies, callback addresses or JWTs in an error message.
                _status.value = currentStatus(error = true)
            } finally {
                listener?.close()
                listener = null
            }
        }
    }

    fun cancel() { login?.cancel(); listener?.close(); _status.value = currentStatus() }

    suspend fun disconnect(): Unit = withContext(NonCancellable) {
        val pending = login
        this@ChatGptAuth.cancel()
        pending?.join()
        lock.withLock {
            val session = readSession()
            val revoked = if (session?.has("refresh_token") == true) {
                try {
                    val response = transport.send(HttpRequest("POST", "$AUTH/api/accounts/oauth/revoke",
                        mapOf("content-type" to "application/x-www-form-urlencoded"), form(mapOf(
                            "token" to session.getString("refresh_token"), "token_type_hint" to "refresh_token",
                            "client_id" to session.getString("client_id")))))
                    response.status == 200
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
            } else true
            // Retain the registration, but no tokens, for later reauthorization on the same host.
            if (session != null) saveSession(JSONObject().apply {
                for (key in listOf("client_id", "subject", "email")) put(key, session.get(key))
            })
            registry.remove("chatgpt")
            _status.value = Status(revocationUnconfirmed = !revoked)
        }
    }

    fun client(): ModelClient = ChatGptClient(transport, ::accessToken)

    suspend fun accessToken(): String = lock.withLock {
        var session = readSession() ?: throw AiException.NotConfigured("sign in to ChatGPT")
        if (!session.has("access_token")) throw AiException.NotConfigured("sign in to ChatGPT")
        if (now() >= session.getLong("saved_at") + session.getLong("expires_in") * 1000 - 60_000) {
            val updated = try { exchange(mapOf("grant_type" to "refresh_token",
                "client_id" to session.getString("client_id"), "refresh_token" to session.getString("refresh_token"),
                "resource" to RESOURCE)) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw AiException.KeyRefused("sign in to ChatGPT again") }
            // A refresh may omit unchanged values. Persist rotating credentials in one encrypted write.
            for (key in listOf("client_id", "subject", "email", "refresh_token", "id_token", "scope")) {
                if (!updated.has(key)) updated.put(key, session.get(key))
            }
            updated.put("saved_at", now())
            validateTokens(updated)
            currentCoroutineContext().ensureActive()
            saveSession(updated)
            session = updated
        }
        session.getString("access_token")
    }

    private fun readSession(): JSONObject? = runCatching {
        storage.read(SESSION)?.let { JSONObject(String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8)) }
    }.getOrNull()
    private fun saveSession(value: JSONObject) = storage.write(SESSION, Base64.getEncoder().encodeToString(cipher.encrypt(value.toString().toByteArray())))

    private suspend fun exchange(params: Map<String, String>): JSONObject {
        val response = transport.send(HttpRequest("POST", "$AUTH/api/accounts/oauth/token",
            mapOf("content-type" to "application/x-www-form-urlencoded"), form(params)))
        require(response.status in 200..299)
        return JSONObject(response.body)
    }

    private fun validateTokens(tokens: JSONObject) {
        require(tokens.getString("token_type").equals("Bearer", true))
        require(tokens.getString("access_token").isNotBlank() && tokens.getString("refresh_token").isNotBlank())
        require(tokens.getLong("expires_in") in 1..31_536_000)
        require("chatgpt.tokens.use.direct" in tokens.getString("scope").split(" "))
    }

    private suspend fun awaitCallback(socket: ServerSocket, expectedState: String): Map<String, String> {
        while (currentCoroutineContext().isActive) {
            val peer = try { socket.accept() } catch (_: java.net.SocketTimeoutException) { continue }
            peer.use {
                peer.soTimeout = 2000
                val reader = peer.getInputStream().bufferedReader()
                val line = try {
                    buildString {
                        while (length < 8192) {
                            val char = reader.read()
                            if (char == -1 || char == 10) break
                            append(char.toChar())
                        }
                    }
                } catch (_: java.net.SocketTimeoutException) { return@use }
                val target = line.split(" ").getOrNull(1).orEmpty()
                val uri = runCatching { URI(target) }.getOrNull()
                val values = runCatching { query(uri?.rawQuery.orEmpty()) }.getOrDefault(emptyMap())
                val valid = line.length < 8192 && line.startsWith("GET ") && uri?.path == "/auth/callback" && values["state"] == expectedState
                val text = if (valid) callbackReceived else callbackRefused
                val bytes = text.toByteArray()
                peer.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray() + bytes)
                if (valid) return values
            }
        }
        throw CancellationException()
    }

    companion object {
        const val AUTH = "https://auth.openai.com"
        const val RESOURCE = "https://api.openai.com/v1"
        private const val SESSION = "chatgpt-session.enc"
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun random(): String = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        internal fun form(values: Map<String, String>) = values.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        internal fun query(value: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            value.split("&").filter { it.isNotBlank() }.forEach {
                val pair = it.split("=", limit = 2)
                val key = URLDecoder.decode(pair[0], "UTF-8")
                require(key !in result)
                result[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            }
            return result
        }
        internal fun validateIdentity(jwt: String, jwks: JSONObject, client: String, nonce: String, time: Long): JSONObject {
            val parts = jwt.split('.')
            require(parts.size == 3)
            fun decoded(part: String) = Base64.getUrlDecoder().decode(part)
            val header = JSONObject(String(decoded(parts[0]), Charsets.UTF_8))
            require(header.getString("alg") == "RS256")
            val keys = jwks.getJSONArray("keys")
            val key = (0 until keys.length()).map { keys.getJSONObject(it) }.single {
                it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA"
            }
            require(key.optString("use", "sig") == "sig" && key.optString("alg", "RS256") == "RS256")
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
                BigInteger(1, decoded(key.getString("n"))), BigInteger(1, decoded(key.getString("e"))),
            ))
            require(Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray()); verify(decoded(parts[2]))
            })
            val claims = JSONObject(String(decoded(parts[1]), Charsets.UTF_8))
            require(claims.getString("iss") == AUTH && claims.getLong("exp") > time / 1000)
            val audiences = claims.optJSONArray("aud")
            require(if (audiences == null) claims.getString("aud") == client
                else (0 until audiences.length()).any { audiences.getString(it) == client })
            if (audiences != null && audiences.length() > 1) require(claims.getString("azp") == client)
            require(claims.getString("nonce") == nonce && claims.getString("sub").isNotBlank())
            require(!claims.has("nbf") || claims.getLong("nbf") <= time / 1000 + 30)
            return claims
        }
    }
}
