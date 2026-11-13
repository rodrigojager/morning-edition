package dobra.app.sources

import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * The real way out of the collector engine: a plain GET with a connect and read timeout and a limit on the size of
 * the body. It says what it is (the user agent names the app), never retries and never disguises the request; a
 * refusal (403, 429) comes back as the status the site gave. The engine has already checked the address.
 */
class HttpFetchTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val maxBodyBytes: Int = 5_000_000,
    private val userAgent: String = "PrimeiraDobra/0.1 (personal reader app; reads public pages for one person)",
) : FetchTransport {

    override suspend fun get(url: String): FetchResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/html;q=0.9, */*;q=0.5")
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val type = connection.contentType
            val body = stream?.use { read(it, charsetOf(type)) }.orEmpty()
            FetchResponse(status, body, type, finalUrl = connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: InputStream, charset: Charset): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxBodyBytes) throw IOException("the response is larger than $maxBodyBytes bytes")
            out.write(buffer, 0, n)
        }
        return out.toString(charset.name())
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()?.trim('"')
        return try {
            if (name.isNullOrEmpty()) Charsets.UTF_8 else Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }
}
