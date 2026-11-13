package dobra.app.turn

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlin.math.roundToInt

/**
 * The pictures of the screens the turn needs, made from each screen's own composable content: every screen drawn
 * inside a [PictureBox] records its drawing into a graphics layer, and [take] renders that layer into a bitmap.
 */
internal class PagePictures<K> {
    private class Entry(val layer: GraphicsLayer) {
        var recorded = false
    }

    private val entries = HashMap<K, Entry>()

    /**
     * The picture of screen [key], scaled by [scale], once a [PictureBox] for it has drawn; null when none did within
     * the allowed frames or the picture could not be made.
     */
    suspend fun take(key: K, scale: Float, frames: Int = 90): Bitmap? {
        var entry: Entry? = null
        for (n in 0 until frames) {
            entry = entries[key]?.takeIf { it.recorded && it.layer.size.width > 0 && it.layer.size.height > 0 }
            if (entry != null) break
            withFrameNanos { }
        }
        val e = entry ?: return null
        return try {
            val image = e.layer.toImageBitmap().asAndroidBitmap()
            val soft = if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
            if (scale >= 1f) soft else {
                val w = (soft.width * scale).roundToInt().coerceAtLeast(1)
                val h = (soft.height * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(soft, w, h, true).also { if (it !== soft) soft.recycle() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun register(key: K, layer: GraphicsLayer): Any = Entry(layer).also { entries[key] = it }

    internal fun unregister(key: K, token: Any) {
        if (entries[key] === token) entries.remove(key)
    }

    internal fun recorded(token: Any) {
        (token as Entry).recorded = true
    }
}

/**
 * Draws [content] and records it as the picture of screen [key]. With [shown] false the content is recorded but not
 * drawn: a screen composed only for its picture. Without [pictures] it is a plain box.
 */
@Composable
internal fun <K> PictureBox(
    key: K,
    pictures: PagePictures<K>?,
    shown: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (pictures == null) {
        Box(modifier) { content() }
        return
    }
    val layer = rememberGraphicsLayer()
    val token = remember(key, layer, pictures) { pictures.register(key, layer) }
    DisposableEffect(token) { onDispose { pictures.unregister(key, token) } }
    Box(
        modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            pictures.recorded(token)
            if (shown) drawLayer(layer)
        },
    ) { content() }
}
