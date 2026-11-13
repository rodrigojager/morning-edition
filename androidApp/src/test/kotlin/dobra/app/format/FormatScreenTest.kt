package dobra.app.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.edition.EditionTab
import dobra.app.shell.AppFonts
import dobra.app.shell.AppTheme
import dobra.app.shell.LocalReaderColors
import dobra.model.Blocks
import dobra.model.ItemKind
import dobra.model.Magazine
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class FormatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = Files.createTempDirectory("dobra-format-test").toFile()
    private val store: Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val id = EditionFixture.magazine.id
    private val edition = EditionFixture.edition
    private val withQuote = edition.items.filter { i -> i.blocks.any { it.type == "quote" } }
    private var showEdition by mutableStateOf(false)
    private var accent: androidx.compose.ui.graphics.Color? = null

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    private fun showEditor() = compose.setContent {
        AppTheme {
            accent = LocalReaderColors.current.accent
            if (!showEdition) {
                FormatScreen(store, id, onBack = {}, onOpenEdition = { showEdition = true })
            } else {
                val magazine by produceState<Magazine?>(null) { value = store.magazines.get(id) }
                magazine?.let { EditionTab(it, edition, onBackToLibrary = {}) }
            }
        }
    }

    private fun tag(t: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(t)
    }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    private fun summaryIs(text: String) = compose.waitUntil(5_000) {
        runCatching { compose.onNodeWithTag("format-check-summary").assertTextEquals(text) }.isSuccess
    }

    private fun textOf(t: String): String =
        tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun openEdition() {
        click("format-see-edition")
        tag("edition-scroll")
    }

    private fun chapterHas(item: String, block: String): Boolean {
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("chapter-$item"))
        compose.waitForIdle()
        return compose.onAllNodes(hasTestTag("block-$block") and hasAnyAncestor(hasTestTag("chapter-$item")))
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun listsTheNineteenTypesWithTitleAndSourcesRequired() {
        showEditor()
        assertEquals(19, Blocks.all.size)
        summaryIs("14 de 14 itens dentro do formato")
        tag("format-count").assertTextEquals("19 de 19 aceitos")
        Blocks.all.forEach { tag("format-block-${it.id}") }
        listOf("title", "sources").forEach {
            tag("format-required-$it").assertTextEquals("obrigatório")
            assertTrue(compose.onAllNodesWithTag("format-toggle-$it").fetchSemanticsNodes().isEmpty())
        }
        Blocks.all.filter { !it.required }.forEach { tag("format-toggle-${it.id}") }
        tag("format-label-quote").assertTextEquals("Citação")
    }

    @Test
    fun refusingQuoteNamesTheItemsAndTheEditionStopsDrawingQuotes() {
        assertTrue(withQuote.isNotEmpty())
        val story = withQuote.first { it.kind == ItemKind.STORY }
        showEditor()
        summaryIs("14 de 14 itens dentro do formato")
        click("format-toggle-quote")
        summaryIs("${14 - withQuote.size} de 14 itens dentro do formato")
        withQuote.forEach { item ->
            val line = textOf("format-check-item-${item.id}")
            assertTrue(line, "“Citação”" in line)
            val title = checkItem(item, dobra.model.Format()).title
            assertTrue(line, title in line)
        }
        tag("format-count").assertTextEquals("18 de 19 aceitos")
        assertEquals(1 + 0, store.let { runBlocking { it.magazines.get(id)!!.version } } - EditionFixture.magazine.version)

        click("format-schema-toggle")
        val schema = textOf("format-schema")
        assertFalse(schema, "\"quote\"" in schema)
        assertTrue(schema, "\"paragraph\"" in schema)

        openEdition()
        assertFalse(chapterHas(story.id, "quote"))
        assertTrue(chapterHas(story.id, "title"))
    }

    @Test
    fun aLookIsAppliedInTheEditionAndListedAsData() {
        showEditor()
        click("look-open-paragraph")
        click("look-paragraph-font-mono")
        click("look-paragraph-size-1.3")
        click("look-paragraph-color-accent")
        click("look-paragraph-align-center")
        click("format-looks-toggle")
        val data = textOf("format-looks")
        listOf("\"paragraph\"", "\"mono\"", "\"1.3\"", "\"accent\"", "\"center\"").forEach { assertTrue(data, it in data) }
        val stored = runBlocking { store.magazines.get(id)!!.looks["paragraph"]!! }
        assertEquals(listOf("mono", "1.3", "accent", "center"), listOf(stored.font, stored.size, stored.color, stored.align))

        // the schema does not change with a look
        click("format-schema-toggle")
        assertEquals(schemaText(EditionFixture.magazine.format, EditionFixture.magazine.name), textOf("format-schema"))

        openEdition()
        val story = edition.items.first { i -> i.kind == ItemKind.STORY && i.blocks.any { it.type == "paragraph" } }
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag("chapter-${story.id}"))
        compose.waitForIdle()
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag("block-paragraph"))).fetchSemanticsNodes()
        val style = nodes.mapNotNull { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.firstOrNull()?.layoutInput?.style
        }.first()
        assertEquals(TextAlign.Center, style.textAlign)
        assertEquals(AppFonts.mono, style.fontFamily)
        assertEquals(accent, style.color)
        assertNotEquals(0f, style.fontSize.value)
    }

    @Test
    fun resetGoesBackToTheDefaultFormat() {
        val story = withQuote.first { it.kind == ItemKind.STORY }
        showEditor()
        click("format-toggle-quote")
        click("look-open-paragraph")
        click("look-paragraph-size-1.15")
        summaryIs("${14 - withQuote.size} de 14 itens dentro do formato")
        click("format-reset")
        summaryIs("14 de 14 itens dentro do formato")
        tag("format-count").assertTextEquals("19 de 19 aceitos")
        compose.waitUntil(5_000) { runBlocking { store.magazines.get(id)!!.looks.isEmpty() } }
        click("format-looks-toggle")
        assertEquals("{}", textOf("format-looks"))
        openEdition()
        assertTrue(chapterHas(story.id, "quote"))
    }

    @Test
    fun aMagazineWithNoEditionStillWorksAndSaysThereIsNothingToCheck() {
        runBlocking { store.editions.delete(edition.key) }
        showEditor()
        tag("format-check-none")
        assertTrue(compose.onAllNodesWithTag("format-check-summary").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("format-see-edition").fetchSemanticsNodes().isEmpty())
        click("format-toggle-quote")
        tag("format-count").assertTextEquals("18 de 19 aceitos")
        compose.waitUntil(5_000) { runBlocking { !store.magazines.get(id)!!.format.accepts("quote") } }
        tag("format-check-none")
    }
}
