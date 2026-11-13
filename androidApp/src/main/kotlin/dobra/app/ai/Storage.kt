package dobra.app.ai

import dobra.ai.provider.AiException
import dobra.ai.provider.BlobStorage
import dobra.ai.provider.HttpRequest
import dobra.ai.provider.HttpResponse
import dobra.ai.provider.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Blobs as files of a private folder, apart from the reader's store folder. */
class FileBlobStorage(private val dir: File) : BlobStorage {
    private fun file(name: String) = File(dir, name)
    override fun read(name: String): String? = file(name).takeIf { it.isFile }?.readText()
    override fun write(name: String, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file(name))) {
            file(name).writeText(text)
            tmp.delete()
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }
}

/** The real network door. Not used by any test. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        try {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = request.method
                // Credentials must never follow an endpoint redirect to a different origin.
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 120_000
                request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                request.body?.let {
                    connection.doOutput = true
                    connection.outputStream.use { out -> out.write(it.encodeToByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.bufferedReader()?.use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (result.length + count > 8 * 1024 * 1024) throw AiException.BadAnswer("response too large")
                        result.append(buffer, 0, count)
                    }
                    result.toString()
                } ?: ""
                val headers = connection.headerFields.filterKeys { it != null }
                    .map { (k, v) -> k.lowercase() to v.joinToString(",") }.toMap()
                HttpResponse(status, headers, body)
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            throw AiException.Unreachable(e::class.simpleName ?: "io error")
        }
    }
}
