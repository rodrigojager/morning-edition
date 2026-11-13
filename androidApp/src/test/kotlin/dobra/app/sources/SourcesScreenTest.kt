package dobra.app.sources

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.shell.AppTheme
import dobra.model.CollectionState
import dobra.store.Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val edition = EditionFixture.edition
    private val magazine = EditionFixture.magazine

    @Test
    fun showsTheTenLinesOfTheDemonstrationCollectionRecordWithTheirRealResults() {
        assertEquals(10, edition.collection.size)
        compose.setContent { AppTheme(Theme.PAPER, 1f) { SourcesScreen(magazine, edition) } }
        compose.onNodeWithTag("sources-edition").assertTextEquals("Edição nº ${edition.number} · ${edition.date} · ${magazine.name}")

        edition.collection.forEachIndexed { i, line ->
            compose.onNodeWithTag("collection-line-$i").performScrollTo()
            compose.onNodeWithTag("collection-source-$i").assertTextEquals(line.source)
            val state = when (line.state) {
                CollectionState.ANSWERED -> "Respondeu"
                CollectionState.PARTIAL -> "Respondeu em parte"
                CollectionState.FAILED -> "Falhou"
            }
            val count = if (line.records == 1) "1 registro" else "${line.records} registros"
            compose.onNodeWithTag("collection-state-$i").assertTextEquals("$state · $count")
            if (line.note != null) compose.onNodeWithTag("collection-note-$i").assertTextEquals(line.note!!)
        }
        // The failing source is shown as failing, with its reason.
        compose.onNodeWithTag("collection-state-9").assertTextEquals("Falhou · 0 registros")
        compose.onNodeWithTag("collection-note-9").assertTextEquals("403 para leitura automática")
        compose.onNodeWithTag("collection-state-4").assertTextEquals("Respondeu em parte · 75 registros")
    }

    @Test
    fun withoutAnEditionTheTabSaysSo() {
        compose.setContent { AppTheme(Theme.PAPER, 1f) { SourcesScreen(magazine, null) } }
        compose.onNodeWithTag("sources-no-edition").assertExists()
    }
}
