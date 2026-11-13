package dobra.app.edition

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.shell.AppFonts
import dobra.app.shell.AppTheme
import dobra.app.shell.ReaderPalettes
import dobra.model.Block
import dobra.model.Blocks
import dobra.model.Format
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.Look
import dobra.store.Feedback
import dobra.store.Keys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockViewsTest {
    @get:Rule
    val compose = createComposeRule()

    private val magazine = EditionFixture.magazine
    private val edition = EditionFixture.edition
    private val stories = edition.items.filter { it.kind == ItemKind.STORY }
    private val notes = edition.items.filter { it.kind == ItemKind.NOTE }

    private fun plain(markup: String) = parseInline(markup).joinToString("") {
        when (it) {
            is InlinePiece.Plain -> it.text
            is InlinePiece.Code -> it.text
            is InlinePiece.Bold -> it.text
            is InlinePiece.Link -> it.label
        }
    }

    private fun showEdition(
        m: dobra.model.Magazine = magazine,
        marks: FakeMarks? = null,
    ) = compose.setContent {
        AppTheme { EditionTab(m, edition, onBackToLibrary = {}, onPreviousEdition = null, marks = marks) }
    }

    private fun showBlock(block: Block, loader: ImageLoader = ImageLoader { null }) = compose.setContent {
        AppTheme { CompositionLocalProvider(LocalImageLoader provides loader) { BlockView(block) } }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("edition-scroll").performScrollToNode(hasTestTag(tag))
    }

    private fun inBlock(tag: String, matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(tag))

    private fun hasNodes(tag: String, text: String, substring: Boolean = false) = compose
        .onAllNodes(inBlock(tag, hasText(text, substring = substring))).fetchSemanticsNodes().isNotEmpty()

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    private fun textNode(tag: String, text: String, substring: Boolean = false) =
        compose.onAllNodes(inBlock(tag, hasText(text, substring = substring))).fetchSemanticsNodes().first()

    private fun blockTags(chapterTag: String): List<String> =
        compose.onAllNodes(
            SemanticsMatcher("block tag") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("block-") == true
            } and hasAnyAncestor(hasTestTag(chapterTag)),
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    // ---- every block type ----

    @Test
    fun everyStoryShowsAllItsBlocksInOrder() {
        showEdition()
        stories.forEach { story ->
            val chapter = "chapter-${story.id}"
            scrollTo(chapter)
            assertEquals(story.id, story.blocks.map { "block-${it.type}" }, blockTags(chapter))
        }
        // between them the stories carry every standard type but the video
        val seen = stories.flatMap { s -> s.blocks.map { it.type } }.toSet()
        assertTrue(seen.containsAll(Blocks.ids - "video" - "image"))
    }

    @Test
    fun contentOfTheRichBlocksIsOnScreen() {
        showEdition()
        fun firstOf(type: String) = stories.first { s -> s.blocks.any { it.type == type } }
            .let { s -> s to s.blocks.first { it.type == type } }

        val (glossaryStory, glossary) = firstOf("glossary")
        scrollTo("chapter-${glossaryStory.id}")
        glossary.pairs("terms").forEach { (term, definition) ->
            assertTrue(term, hasNodes("block-glossary", plain(term)))
            assertTrue(definition, hasNodes("block-glossary", plain(definition)))
        }

        val (sheetStory, sheet) = firstOf("factsheet")
        scrollTo("chapter-${sheetStory.id}")
        sheet.pairs("rows").forEach { (label, value) ->
            assertTrue(label, hasNodes("block-factsheet", label))
            assertTrue(value, hasNodes("block-factsheet", plain(value)))
        }

        val (quoteStory, quote) = firstOf("quote")
        scrollTo("chapter-${quoteStory.id}")
        val who = quote.text("who")!!
        val whoNode = textNode("block-quote", who, substring = true)
        val links = layout(whoNode).layoutInput.text.getLinkAnnotations(0, layout(whoNode).layoutInput.text.length)
        assertEquals(quote.text("url"), (links.single().item as LinkAnnotation.Url).url)
        assertTrue(hasNodes("block-quote", quote.text("text")!!, substring = true))

        val (evidenceStory, evidence) = firstOf("evidence")
        scrollTo("chapter-${evidenceStory.id}")
        evidence.pairs("rows").forEach { row ->
            assertTrue(row[0], hasNodes("block-evidence", row[0]))
            assertTrue(row[1], hasNodes("block-evidence", plain(row[1])))
        }

        val (codeStory, code) = firstOf("code")
        scrollTo("chapter-${codeStory.id}")
        val codeNode = textNode("block-code", code.text("code")!!)
        assertEquals(AppFonts.mono, layout(codeNode).layoutInput.style.fontFamily)

        val (factsStory, facts) = firstOf("facts")
        scrollTo("chapter-${factsStory.id}")
        assertTrue(hasNodes("block-facts", "Fato verificado na fonte"))
        facts.strings("items").forEach { assertTrue(it, hasNodes("block-facts", plain(it))) }

        val (hypStory, hyp) = firstOf("hypothesis")
        scrollTo("chapter-${hypStory.id}")
        assertTrue(hasNodes("block-hypothesis", "Hipótese para o seu contexto · não verificada"))
        hyp.strings("paragraphs").forEach { assertTrue(it, hasNodes("block-hypothesis", plain(it))) }
    }

    // ---- image and video ----

    private val imageBlock = notes.flatMap { it.blocks }.plus(stories.flatMap { it.blocks }).first { it.type == "image" }

    @Test
    fun imageShowsCaptionAndCredit() {
        showBlock(imageBlock) { android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() }
        compose.waitForIdle()
        compose.onNodeWithTag("image-loaded").assertExists()
        compose.onNodeWithTag("image-unavailable").assertDoesNotExist()
        compose.onNode(hasContentDescription(imageBlock.text("alt")!!)).assertExists()
        val line = textNode("block-image", plain(imageBlock.text("caption")!!), substring = true)
        assertTrue(layout(line).layoutInput.text.text.contains(imageBlock.text("credit")!!))
    }

    @Test
    fun failedImageShowsTheSentenceInItsPlace() {
        showBlock(imageBlock) { null }
        compose.waitForIdle()
        compose.onNodeWithTag("image-loaded").assertDoesNotExist()
        compose.onNode(hasText("Imagem da fonte indisponível no momento; o texto não depende dela.")).assertExists()
        assertTrue(hasNodes("block-image", imageBlock.text("credit")!!, substring = true))
    }

    @Test
    fun videoIsACardWithTitleCreditAndLink() {
        val url = "https://example.org/video/42"
        val block = Block(
            "video",
            mapOf("url" to JsonPrimitive(url), "title" to JsonPrimitive("Como funciona"), "credit" to JsonPrimitive("Canal Exemplo")),
        )
        showBlock(block)
        assertTrue(hasNodes("block-video", "Como funciona"))
        assertTrue(hasNodes("block-video", "Canal Exemplo"))
        val link = textNode("block-video", url)
        val text = layout(link).layoutInput.text
        assertEquals(url, (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url)
    }

    // ---- looks and the format ----

    @Test
    fun looksOfTheMagazineAreApplied() {
        val styled = magazine.copy(looks = mapOf("summary" to Look(color = "accent"), "paragraph" to Look(size = "1.3")))
        showEdition(styled)
        val story = stories.first { s -> s.blocks.any { it.type == "paragraph" } }
        scrollTo("chapter-${story.id}")

        val summary = story.blocks.first { it.type == "summary" }.text("text")!!
        val summaryStyle = layout(textNode("block-summary", plain(summary))).layoutInput.style
        assertEquals(ReaderPalettes.paper.accent, summaryStyle.color)

        val paragraph = story.blocks.first { it.type == "paragraph" }.text("text")!!
        val paragraphStyle = layout(textNode("block-paragraph", plain(paragraph))).layoutInput.style
        assertEquals(17f * 1.3f, paragraphStyle.fontSize.value, 0.01f)
    }

    @Test
    fun aRefusedTypeAndItsOrphanSectionTitleAreNotDrawn() {
        val story = stories.first { s -> s.blocks.any { it.type == "glossary" } }
        val orphan = story.blocks[story.blocks.indexOfFirst { it.type == "glossary" } - 1]
        assertEquals("section", orphan.type)
        val restricted = magazine.copy(format = magazine.format.without("glossary"))
        showEdition(restricted)
        scrollTo("chapter-${story.id}")
        assertFalse("block-glossary" in blockTags("chapter-${story.id}"))
        assertFalse(
            compose.onAllNodes(inBlock("chapter-${story.id}", hasText(orphan.text("text")!!))).fetchSemanticsNodes().isNotEmpty(),
        )
        val other = story.blocks.first { it.type == "section" && it !== orphan }
        assertTrue(hasNodes("chapter-${story.id}", other.text("text")!!))
    }

    @Test
    fun shownBlocksDropsTrailingAndDoubledSectionTitles() {
        fun b(type: String) = Block(type, mapOf("text" to JsonPrimitive(type)))
        val blocks = listOf(b("title"), b("section"), b("callout"), b("section"), b("paragraph"), b("section"))
        val shown = shownBlocks(blocks, Format(listOf("paragraph", "section")))
        assertEquals(listOf("title", "section", "paragraph"), shown.map { it.type })
    }

    // ---- buttons ----

    @Test
    fun saveFeedbackAndReadAreRecorded() {
        val marks = FakeMarks()
        showEdition(marks = marks)
        val story = stories[1]
        val key = Keys.item(edition.key, story.id)
        scrollTo("chapter-${story.id}")
        compose.waitForIdle()
        assertTrue(key in marks.read)

        compose.onNode(hasText("Salvar") and hasTestTag("save-${story.id}")).assertExists()
        compose.onNodeWithTag("save-${story.id}").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Salva") and hasTestTag("save-${story.id}")).assertExists()
        assertEquals(listOf(key), marks.savedKeys)

        compose.onNodeWithTag("more-${story.id}").performClick()
        compose.waitForIdle()
        assertEquals(Feedback.MORE, marks.feedbacks[key])
        compose.onNodeWithTag("less-${story.id}").performClick()
        compose.waitForIdle()
        assertEquals(Feedback.LESS, marks.feedbacks[key])
        compose.onNodeWithTag("less-${story.id}").performClick()
        compose.waitForIdle()
        assertNull(marks.feedbacks[key])

        compose.onNodeWithTag("ask-${story.id}").assertExists()
        compose.onNodeWithTag("save-${story.id}").performClick()
        compose.waitForIdle()
        assertTrue(marks.savedKeys.isEmpty())
    }

    @Test
    fun everyShortNoteHasSavingFeedbackAndReadState() {
        val marks = FakeMarks()
        showEdition(marks = marks)
        val note = notes[0]
        scrollTo("chapter-${note.id}")
        compose.onNode(hasText("Salvar") and hasTestTag("save-${note.id}")).assertExists()
        compose.onNodeWithTag("save-${note.id}").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Salva") and hasTestTag("save-${note.id}")).assertExists()
        assertEquals(listOf(Keys.item(edition.key, note.id)), marks.savedKeys)
        assertTrue(Keys.item(edition.key, note.id) in marks.read)
        compose.onNodeWithTag("more-${note.id}").performClick()
        compose.waitForIdle()
        assertEquals(Feedback.MORE, marks.feedbacks[Keys.item(edition.key, note.id)])
        compose.onNodeWithTag("less-${note.id}").performClick()
        compose.waitForIdle()
        assertEquals(Feedback.LESS, marks.feedbacks[Keys.item(edition.key, note.id)])
    }

    // ---- plates ----

    @Test
    fun eightMotifsDrawAndTheIllustrationIsAnnouncedAsGenerated() {
        assertEquals(8, Motifs.names.size)
        Motifs.names.forEach { name ->
            var shapes = 0
            Motifs.of(name).invoke(object : PlateScope {
                override fun circle(cls: String, cx: Float, cy: Float, r: Float, dash: Float?, dashGap: Float) { shapes++ }
                override fun rect(cls: String, x: Float, y: Float, w: Float, h: Float, rx: Float) { shapes++ }
                override fun path(cls: String, d: String, dash: Float?, dashGap: Float, stroke: PlateInk?, width: Float?, round: Boolean) {
                    shapes++
                }
            })
            assertTrue(name, shapes > 3)
        }

        showEdition()
        val motifs = stories.map { s ->
            val art = s.blocks.first { it.type == "illustration" }.fields["art"] as JsonObject
            (art["motif"] as JsonPrimitive).content to (art["alt"] as JsonPrimitive).content
        }
        assertEquals(Motifs.names, motifs.map { it.first }.toSet())
        stories.forEachIndexed { i, s ->
            scrollTo("chapter-${s.id}")
            compose.onNodeWithTag("plate-${motifs[i].first}").assertExists()
            compose.onNode(hasContentDescription("Ilustração gerada pelo app: ${motifs[i].second}")).assertExists()
        }
    }
}
