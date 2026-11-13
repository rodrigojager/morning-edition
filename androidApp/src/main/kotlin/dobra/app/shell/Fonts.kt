package dobra.app.shell

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dobra.app.R

/**
 * The three typefaces of the app, all open-licence (SIL OFL) variable fonts bundled in `res/font`.
 * Nothing is fetched from the network.
 */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    private fun family(res: Int, vararg weights: Int) = FontFamily(
        weights.map { w ->
            Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    /** Fraunces: headlines and the wordmark. */
    val display: FontFamily = family(R.font.fraunces, 400, 620, 650, 700)

    /** Newsreader: running text. */
    val text: FontFamily = family(R.font.newsreader, 400, 600, 700)

    /** JetBrains Mono: labels, code and metadata. */
    val mono: FontFamily = family(R.font.jetbrains_mono, 400, 600)
}
