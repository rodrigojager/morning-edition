package dobra.app.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import dobra.store.Theme

/** The colours of one reading theme (tokens of the prototype's `.app`). */
data class ReaderColors(
    val bg: Color,
    val page: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val rule: Color,
    val accent: Color,
    val accentInk: Color,
    val dark: Boolean,
)

object ReaderPalettes {
    val paper = ReaderColors(
        bg = Color(0xFFEFE9DD), page = Color(0xFFFAF6EE), ink = Color(0xFF1B1A17), ink2 = Color(0xFF48443C),
        ink3 = Color(0xFF6D675C), rule = Color(0xFFD6CDBB), accent = Color(0xFFC2311D),
        accentInk = Color(0xFFFFFAF2), dark = false,
    )
    val sepia = ReaderColors(
        bg = Color(0xFFE4D6BA), page = Color(0xFFEFE3C9), ink = Color(0xFF2F2518), ink2 = Color(0xFF54452F),
        ink3 = Color(0xFF6C5D44), rule = Color(0xFFCBB88F), accent = Color(0xFF96380F),
        accentInk = Color(0xFFFFF6E6), dark = false,
    )
    val night = ReaderColors(
        bg = Color(0xFF0E1013), page = Color(0xFF15181D), ink = Color(0xFFECE6DA), ink2 = Color(0xFFBDB6A8),
        ink3 = Color(0xFF8F887B), rule = Color(0xFF2B3038), accent = Color(0xFFFF7D5C),
        accentInk = Color(0xFF170B07), dark = true,
    )

    fun of(theme: Theme): ReaderColors = when (theme) {
        Theme.PAPER -> paper
        Theme.SEPIA -> sepia
        Theme.NIGHT -> night
    }
}

val LocalReaderColors = staticCompositionLocalOf { ReaderPalettes.paper }

/** Scale factor of the reading text (1.0 is the default), from the device settings. */
val LocalTextScale = staticCompositionLocalOf { 1f }

/** The background colour a screen paints, exposed to tests as a semantics property. */
val PageBackground = SemanticsPropertyKey<Color>("PageBackground")
var SemanticsPropertyReceiver.pageBackground by PageBackground

/** Material 3 theme of the app, driven by the reader's theme (Papel, Sépia or Noite). */
@Composable
fun AppTheme(theme: Theme = Theme.PAPER, textScale: Float = 1f, content: @Composable () -> Unit) {
    val colors = ReaderPalettes.of(theme)
    val scheme = if (colors.dark) {
        darkColorScheme(
            background = colors.bg, surface = colors.page, onBackground = colors.ink, onSurface = colors.ink,
            primary = colors.accent, onPrimary = colors.accentInk, outline = colors.rule,
        )
    } else {
        lightColorScheme(
            background = colors.bg, surface = colors.page, onBackground = colors.ink, onSurface = colors.ink,
            primary = colors.accent, onPrimary = colors.accentInk, outline = colors.rule,
        )
    }
    CompositionLocalProvider(LocalReaderColors provides colors, LocalTextScale provides textScale) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
