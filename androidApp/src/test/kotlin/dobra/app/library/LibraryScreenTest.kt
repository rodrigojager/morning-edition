package dobra.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.app.edition.EditionFixture
import dobra.app.shell.AppShell
import dobra.app.shell.Routes
import dobra.app.shell.ShellData
import dobra.seed.Seed
import dobra.store.DeviceSettings
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val root = Files.createTempDirectory("dobra-library-test").toFile()
    private val store: Store = runBlocking {
        openStore(root.path) { SeedContent(EditionFixture.magazine, EditionFixture.edition) }
    }
    private val id = EditionFixture.magazine.id
    private val key = EditionFixture.edition.key
    private var opened: String? = null

    @After
    fun close() {
        store.close()
        root.deleteRecursively()
    }

    private fun showLibrary() = compose.setContent { LibraryScreen(store) { opened = it } }

    private fun tag(t: String): SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithTag(t)
    }

    private fun click(t: String) = tag(t).performScrollTo().performClick()

    private fun gone(t: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isEmpty() }

    private fun openMagazine() {
        showLibrary()
        click("mag-open-$id")
        tag("screen-magazine")
    }

    @Test
    fun libraryCardShowsCadenceSubjectsAndCountsUntilTheEditionIsRead() {
        showLibrary()
        tag("mag-card-$id")
        tag("mag-name-$id").assertTextEquals("Primeira Dobra")
        compose.onNode(hasText("Diária, às 7h") and hasAnyAncestor(hasTestTag("mag-card-$id"))).assertExists()
        compose.onNode(hasText("IA e agentes") and hasAnyAncestor(hasTestTag("mag-topics-$id"))).assertExists()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("mag-counts-$id").assertTextEquals("1 edição · 1 por ler · 0 itens salvos · 10 fontes") }.isSuccess
        }
        runBlocking { store.marks.setEditionRead(key, true) }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("mag-counts-$id").assertTextEquals("1 edição · nenhuma por ler · 0 itens salvos · 10 fontes") }.isSuccess
        }
    }

    @Test
    fun magazinePageShowsRecipeTenSourcesAndTheEdition() {
        openMagazine()
        tag("recipe-subjects")
        compose.onNode(hasText("Segurança") and hasAnyAncestor(hasTestTag("recipe-subjects"))).assertExists()
        tag("recipe-keywords").assertTextEquals("não informados")
        tag("recipe-ai").assertTextEquals("Escrever em Português (Brasil), no tom “mais didático”. Sem instruções a mais.")
        tag("recipe-format").assertTextEquals("19 de 19 tipos de bloco aceitos.")
        click("recipe-format-link")
        tag("screen-format")
        click("back")
        tag("screen-magazine")

        val sources = EditionFixture.magazine.sources
        assertEquals(10, sources.size)
        sources.forEach { s ->
            tag("source-${s.id}")
            compose.onNode(hasText(s.name) and hasAnyAncestor(hasTestTag("source-${s.id}"))).assertExists()
            compose.onNode(hasText(s.url) and hasAnyAncestor(hasTestTag("source-${s.id}"))).assertExists()
            compose.onNode(hasText("Procurar: ${s.liked}") and hasAnyAncestor(hasTestTag("source-${s.id}"))).assertExists()
            tag("source-state-${s.id}").assertTextEquals("sem coletor")
        }
        tag("edition-open-$key").assertTextEquals("Abrir")
        tag("edition-toggle-$key").assertTextEquals("Marcar como lida")
        tag("edition-delete-$key").assertTextEquals("Apagar edição")

        click("edition-open-$key")
        assertEquals(key, opened)
        click("edition-toggle-$key")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithTag("edition-toggle-$key").assertTextEquals("Marcar como não lida") }.isSuccess
        }
        assertTrue(runBlocking { store.marks.isEditionRead(key) })
    }

    @Test
    fun createEditAndDeleteAMagazine() {
        showLibrary()
        click("new-magazine")
        tag("field-name").performTextInput("Mesa de Música")
        tag("field-subjects").performTextInput("jazz, discos")
        click("field-cadence-weekly")
        tag("field-hour").performTextReplacement("18")
        click("form-save")
        tag("mag-card-mesa-de-musica")
        tag("mag-name-mesa-de-musica").assertTextEquals("Mesa de Música")
        tag("mag-counts-mesa-de-musica").assertTextEquals("0 edições · nenhuma por ler · 0 itens salvos · 0 fontes")
        compose.onNode(hasText("Semanal, às 18h") and hasAnyAncestor(hasTestTag("mag-card-mesa-de-musica"))).assertExists()

        // a form with no name is refused
        click("new-magazine")
        click("form-save")
        tag("form-error-name")
        click("form-cancel")

        // delete the new magazine after confirming; cancelling first changes nothing
        click("mag-open-mesa-de-musica")
        click("delete-magazine")
        click("confirm-no")
        gone("confirm")
        click("delete-magazine")
        click("confirm-yes")
        tag("screen-library")
        gone("mag-card-mesa-de-musica")
        assertNull(runBlocking { store.magazines.get("mesa-de-musica") })
        tag("mag-card-$id")
    }

    @Test
    fun renamingTheDemonstrationMagazineShowsOnTheCover() {
        compose.setContent {
            val magazine = store.magazines.observeAll().collectAsState(initial = emptyList()).value.firstOrNull()
                ?: EditionFixture.magazine
            AppShell(
                data = ShellData(magazine, EditionFixture.edition),
                settings = DeviceSettings(),
                onSettings = {},
                initialTab = Routes.LIBRARY,
                store = store,
            )
        }
        click("mag-open-$id")
        click("edit-magazine")
        tag("field-name").performTextReplacement("Dobra da Manhã")
        click("form-save")
        tag("screen-magazine")
        tag("tab-${Routes.EDITION}").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Dobra da Manhã") and hasAnyAncestor(hasTestTag("cover"))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Dobra da Manhã", runBlocking { store.magazines.get(id) }!!.name)
    }

    @Test
    fun deletingTheDemonstrationMagazineRemovesItFromTheLibrary() {
        openMagazine()
        click("delete-magazine")
        click("confirm-yes")
        tag("library-empty")
        gone("mag-card-$id")
    }

    @Test
    fun addsARefusesAndRemovesASource() {
        openMagazine()
        click("add-source")
        tag("source-field-name").performTextInput("Blog do Rust")
        tag("source-field-url").performTextInput("ftp://exemplo.org/feed")
        tag("source-field-liked").performTextInput("anúncios de versões")
        click("source-save")
        tag("source-error")
        assertEquals(10, runBlocking { store.magazines.get(id) }!!.sources.size)

        tag("source-field-url").performTextReplacement("https://blog.rust-lang.org/")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("source-error").fetchSemanticsNodes().isEmpty() }
        click("source-save")
        tag("source-blog-do-rust")
        compose.onNode(hasText("https://blog.rust-lang.org/") and hasAnyAncestor(hasTestTag("source-blog-do-rust"))).assertExists()
        compose.onNode(hasText("Procurar: anúncios de versões") and hasAnyAncestor(hasTestTag("source-blog-do-rust"))).assertExists()
        tag("source-state-blog-do-rust").assertTextEquals("sem coletor")
        assertEquals(11, runBlocking { store.magazines.get(id) }!!.sources.size)

        click("remove-source-blog-do-rust")
        gone("source-blog-do-rust")
        assertEquals(10, runBlocking { store.magazines.get(id) }!!.sources.size)
    }

    @Test
    fun anEditionIsDeletedOnlyAfterConfirmation() {
        openMagazine()
        click("edition-delete-$key")
        tag("confirm")
        click("confirm-no")
        gone("confirm")
        tag("edition-$key")
        assertNotNull(runBlocking { store.editions.get(key) })

        click("edition-delete-$key")
        click("confirm-yes")
        gone("edition-$key")
        tag("editions-empty")
        compose.onNode(hasText("Nenhuma edição") and hasAnyAncestor(hasTestTag("editions-empty"))).assertExists()
        assertNull(runBlocking { store.editions.get(key) })
        assertTrue(runBlocking { store.editions.isDeleted(key) })
    }

    @Test
    fun editingTheRecipeRaisesItsVersionAndLeavesTheEditionAsItWas() = runBlocking {
        val before = store.editions.get(key)!!
        val magazine = store.magazines.get(id)!!
        assertEquals(1, magazine.version)

        val draft = RecipeDraft.of(magazine).copy(keywords = "kotlin, compose", tone = "direto")
        val saved = store.magazines.save(draft.applyTo(magazine, id))
        assertEquals(2, saved.version)
        assertEquals(listOf("kotlin", "compose"), saved.keywords)
        assertEquals(magazine.sources, saved.sources)
        assertEquals(magazine.format, saved.format)

        val after = store.editions.get(key)!!
        assertEquals(before, after)
        assertEquals(1, after.recipeVersion)

        // saving the same form again changes nothing
        assertEquals(2, store.magazines.save(draft.applyTo(saved, id)).version)
        assertFalse(store.editions.isDeleted(key))
    }

    @Test
    fun webAddressesAreOnlyHttpAndHttps() {
        assertTrue(isWebAddress("https://exemplo.org/a"))
        assertTrue(isWebAddress(" http://exemplo.org "))
        assertFalse(isWebAddress("ftp://exemplo.org"))
        assertFalse(isWebAddress("exemplo.org"))
        assertFalse(isWebAddress("https://"))
        assertFalse(isWebAddress("javascript:alert(1)"))
    }
}
