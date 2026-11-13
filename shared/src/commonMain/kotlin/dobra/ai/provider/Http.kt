package dobra.ai.provider

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
) {
    // Headers carry the key: keep it out of any accidental printout.
    override fun toString() = "$method $url"
}

/** [headers] keys are lower case. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** The only door to the network. Tests replace it with recorded answers. Throws [AiException.Unreachable]. */
interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Writes one line per call (method, address, status) to [log]; headers and bodies are never written. */
class LoggingTransport(private val inner: HttpTransport, private val log: (String) -> Unit) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val response = try {
            inner.send(request)
        } catch (e: AiException) {
            log("ai ${request.method} ${request.url} failed: ${e::class.simpleName}")
            throw e
        }
        log("ai ${request.method} ${request.url} -> ${response.status}")
        return response
    }
}
