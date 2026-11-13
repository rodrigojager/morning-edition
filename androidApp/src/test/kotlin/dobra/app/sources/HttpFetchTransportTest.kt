package dobra.app.sources

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** The real transport against a server on this machine only; no test reaches a live site. */
class HttpFetchTransportTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val requests = AtomicInteger()
    private var agent: String? = null
    private val base get() = "http://127.0.0.1:${server.address.port}"

    init {
        fun answer(path: String, status: Int, body: ByteArray, type: String = "text/plain; charset=utf-8", delay: Long = 0) {
            server.createContext(path) { ex ->
                requests.incrementAndGet()
                agent = ex.requestHeaders.getFirst("User-Agent")
                if (delay > 0) Thread.sleep(delay)
                ex.responseHeaders.add("Content-Type", type)
                ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
                ex.close()
            }
        }
        answer("/ok", 200, "Segurança em português".toByteArray())
        answer("/refused", 403, "no robots".toByteArray())
        answer("/slow-down", 429, ByteArray(0))
        answer("/big", 200, ByteArray(10_000) { 'x'.code.toByte() })
        answer("/slow", 200, "late".toByteArray(), delay = 1_500)
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun readsABodyAndNamesItself() = runBlocking {
        val r = HttpFetchTransport().get("$base/ok")
        assertEquals(200, r.status)
        assertEquals("Segurança em português", r.body)
        assertTrue(agent!!.startsWith("PrimeiraDobra/"))
    }

    @Test
    fun aRefusalComesBackAsItIsAndIsAskedOnce() = runBlocking {
        val refused = HttpFetchTransport().get("$base/refused")
        assertEquals(403, refused.status)
        assertEquals(429, HttpFetchTransport().get("$base/slow-down").status)
        assertEquals(2, requests.get())
    }

    @Test
    fun aBodyOverTheLimitIsRefused() = runBlocking {
        try {
            HttpFetchTransport(maxBodyBytes = 1_000).get("$base/big")
            fail("expected the size limit to stop the read")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("larger than 1000"))
        }
    }

    @Test
    fun aSlowAnswerStopsAtTheTimeout() = runBlocking {
        try {
            HttpFetchTransport(readTimeoutMillis = 300).get("$base/slow")
            fail("expected a timeout")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        }
        Unit
    }
}
