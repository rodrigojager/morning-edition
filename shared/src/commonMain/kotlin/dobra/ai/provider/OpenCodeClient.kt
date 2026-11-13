package dobra.ai.provider

/** The public Console inference API for third-party apps. Uses paid credits, not the OpenCode-only free tier. */
class OpenCodeClient(transport: HttpTransport, baseUrl: String, key: String) : ModelClient {
    private val base = baseUrl.trimEnd('/')
    private val client = OpenAiStyleClient(object : HttpTransport {
        override suspend fun send(request: HttpRequest): HttpResponse = transport.send(HttpRequest(
            request.method,
            if (request.method == "GET") "$base/v1/models" else request.url,
            request.headers + ("user-agent" to "primeira-dobra/0.3.3"),
            request.body,
        ))
    }, "$base/openai/v1", key)

    override suspend fun complete(request: AiRequest) = client.complete(request)
    override suspend fun listModels() = client.listModels()

    companion object {
        const val BASE = "https://opencode.ai/inference"
    }
}
