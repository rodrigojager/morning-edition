package dobra.app.edition

import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the picture of a source from its address when the page is read. The result lives in memory only: images are
 * embedded by address and never stored. Replace it through [LocalImageLoader] (tests do).
 */
fun interface ImageLoader {
    /** The decoded picture, or null when it could not be loaded. */
    suspend fun load(url: String): ImageBitmap?
}

/** Fetches an http(s) address with the platform's own connection and decoder; no extra library, no disk cache. */
object NetworkImageLoader : ImageLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_SIDE = 1600

    override suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val u = URL(url)
            if (u.protocol != "https" && u.protocol != "http") return@runCatching null
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("Referer", "")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) return@runCatching null
                    }
                    out.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

val LocalImageLoader = staticCompositionLocalOf<ImageLoader> { NetworkImageLoader }
