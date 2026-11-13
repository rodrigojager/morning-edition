package dobra.make

import dobra.ai.rewrite.ItemRules
import dobra.ai.rewrite.Recorded
import dobra.ai.rewrite.Samples
import dobra.collect.engine.RawRecord
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.SourceResult
import dobra.collect.worker.WorkerProgress
import dobra.collect.worker.WorkerResult
import dobra.format.ItemFormat
import dobra.model.CollectionLine
import dobra.model.CollectionState
import dobra.model.EditionState
import dobra.model.Magazine
import dobra.seed.Seed
import dobra.store.Feedback
import dobra.store.SeedContent
import dobra.store.Store
import dobra.store.openStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Job B end to end on the JVM: saved records, recorded AI answers replayed through a fake transport, the real store.
 * The magazine and the published edition are the seed's.
 */
class EditionMakerTest {
    private val root: File = Files.createTempDirectory("dobra-make-test").toFile()
    private val seedDir = File("seed")
    private val store: Store = runBlocking {
        openStore(root.path) {
            SeedContent(
                Seed.magazine(File(seedDir, Seed.MAGAZINE_FILE).readText()),
                Seed.edition(File(seedDir, Seed.EDITION_FILE).readText()),
            )
        }
    }
    private val today = "2026-11-13"

    @AfterTest
    fun cleanUp() {
        store.close()
        root.deleteRecursively()
    }

    /** The seed magazine with instructions and a tone the requests must carry. */
    private val magazine: Magazine = runBlocking {
        val m = store.magazines.get("primeira-dobra")!!
        store.magazines.save(
            m.copy(
                prompt = "Prefira ferramentas open source que um time .NET possa adotar nesta semana.",
                keywords = listOf("agentes", "segurança"),
            ),
        )
    }

    // ---- saved records ----

    private val tool = RawRecord(
        "acme/agent-guard: guarda de permissões para agentes de código",
        "https://github.com/acme/agent-guard",
        "2026-11-13",
        "agent-guard bloqueia comandos perigosos que um agente de código tenta rodar. Licença MIT. 1.204 estrelas na semana.",
        signal = 1204.0,
        media = listOf("https://raw.example.org/acme/agent-guard/shot.png"),
    )
    private val notes = RawRecord(
        "Notas da versão do .NET 11 preview 3",
        "https://devblogs.microsoft.com/dotnet/net-11-preview-3",
        "2026-11-12",
        "O preview 3 traz melhorias no compilador JIT e no ASP.NET Core.",
    )
    private val bait = RawRecord(
        "10 truques de IA que vão mudar sua vida",
        "https://clickbait.example.com/10-truques",
        "",
        "Você não vai acreditar no número 7.",
    )

    /** A record whose link the seed edition already published. */
    private val again = RawRecord(
        "cloudflare/security-audit-skill",
        "https://github.com/cloudflare/security-audit-skill",
        "2026-11-13",
        "Versão 2 lançada: agora a skill também audita workflows do GitHub Actions.",
    )

    private fun run(fresh: List<RawRecord>, candidates: List<RawRecord> = emptyList()) = WorkerResult(
        magazine.id,
        listOf(
            SourceResult(
                "github-trending", "GitHub Trending", CollectionState.ANSWERED, 24, fresh.size + candidates.size,
                mapOf("agentes" to fresh.size), fresh, candidates,
            ),
            SourceResult(
                "blog-da-apiiro", "Blog da Apiiro", CollectionState.FAILED, 0, 0, emptyMap(), emptyList(), emptyList(),
                reason = "refused by the site (HTTP 403)", refusedStatus = 403,
            ),
        ),
    )

    private fun maker(ai: Recorded, pages: PageReader? = null) =
        EditionMaker(ai.gateway, ai.ledger, store.editions, store.marks, pages, today = { today })

    // ---- recorded answers ----

    private val selection = """{"keep": [
        {"records": ["r1"], "kind": "story", "update": false, "why": "segurança de agentes, open source"},
        {"records": ["r2"], "kind": "note", "update": false, "why": ".NET"}],
        "leftOut": [{"record": "r3", "reason": "Isca de cliques, sem conteúdo próprio."}]}"""

    private val toolItem = """{"id": "agent-guard", "kind": "story", "blocks": [
        {"type": "title", "kicker": "Segurança · Agentes", "text": "Um guarda de permissões para agentes de código"},
        {"type": "summary", "text": "Bloqueia comandos perigosos antes que o agente os rode."},
        {"type": "image", "src": "https://raw.example.org/acme/agent-guard/shot.png", "alt": "tela", "caption": "A tela do projeto",
         "credit": "acme/agent-guard no GitHub", "href": "https://github.com/acme/agent-guard"},
        {"type": "facts", "items": ["Licença MIT.", "1.204 estrelas na semana."]},
        {"type": "hypothesis", "paragraphs": ["Num time .NET, serviria para limitar o que o agente faz no CI."]},
        {"type": "sources", "links": [{"role": "Fonte primária", "label": "acme/agent-guard", "url": "https://github.com/acme/agent-guard"},
          {"role": "Onde foi descoberta", "label": "GitHub Trending", "url": "https://github.com/trending"}]}]}"""

    private val noteItem = """{"id": "dotnet-11-preview-3", "kind": "note", "blocks": [
        {"type": "title", "kicker": ".NET", "text": ".NET 11 preview 3"},
        {"type": "paragraph", "text": "Melhorias no JIT e no ASP.NET Core."},
        {"type": "sources", "links": [{"label": ".NET Blog", "url": "https://devblogs.microsoft.com/dotnet/net-11-preview-3"}]}]}"""

    /** Cites an address that is not among the record's links, and lacks the field "text" of a paragraph. */
    private val invalidNote = """{"id": "dotnet", "kind": "note", "blocks": [
        {"type": "title", "kicker": ".NET", "text": ".NET 11 preview 3"},
        {"type": "paragraph"},
        {"type": "sources", "links": [{"label": "Blog", "url": "https://invented.example.com/dotnet"}]}]}"""

    @Test
    fun savedRecordsAndRecordedAnswersMakeAStoredUnreadEditionOfValidItems() = runBlocking {
        val unreadBefore = store.unread.count(magazine.id)
        val ai = Recorded(listOf(selection, toolItem, noteItem))
        val result = maker(ai).make(magazine, run(listOf(tool, notes, bait)))

        val edition = assertNotNull(result.edition)
        assertNull(result.problem)
        val stored = assertNotNull(store.editions.get(edition.key))
        assertEquals(edition, stored)
        assertEquals("primeira-dobra/$today", stored.key)
        assertEquals(today, stored.date)
        assertEquals(2, stored.number)
        assertEquals(magazine.version, stored.recipeVersion)
        assertEquals(2, magazine.version)
        assertEquals(EditionState.PUBLISHED, stored.state)
        assertEquals(
            listOf(
                CollectionLine("GitHub Trending", 3, CollectionState.ANSWERED),
                CollectionLine("Blog da Apiiro", 0, CollectionState.FAILED, "refused by the site (HTTP 403)"),
            ),
            stored.collection,
        )
        assertEquals(listOf("agent-guard", "dotnet-11-preview-3"), stored.items.map { it.id })
        assertEquals(listOf("story", "note"), stored.items.map { it.kind.name.lowercase() })
        stored.items.forEach { assertTrue(ItemFormat.check(it, magazine.format).ok, it.id) }
        assertEquals("10 truques de IA que vão mudar sua vida", stored.leftOut.single().title)
        assertEquals("Isca de cliques, sem conteúdo próprio.", stored.leftOut.single().reason)

        // The AI cost: three calls of 1000 + 500 tokens at 1 and 4 dollars per million.
        val cost = ai.ledger.totalFor(stored.key)
        assertEquals(3, cost.calls)
        assertEquals(3 * (0.001 + 0.002), cost.costUsd, 1e-9)
        assertEquals(cost, result.cost)

        // Unread in the library: the new edition counts, next to the seed's.
        assertEquals(unreadBefore + 1, store.unread.count(magazine.id))
        assertEquals(stored.key, store.editions.list(magazine.id).first().key)
    }

    @Test
    fun anInvalidAnswerGoesBackWithTheValidatorsMessageAndAfterTheLimitTheItemIsLeftOutWithTheReason() = runBlocking {
        val ai = Recorded(listOf(selection, toolItem, invalidNote, invalidNote, invalidNote))
        val result = maker(ai).make(magazine, run(listOf(tool, notes, bait)))
        val edition = assertNotNull(result.edition)
        assertEquals(listOf("agent-guard"), edition.items.map { it.id })
        assertEquals(5, ai.transport.sent.size)

        // The second call for the note carries the first answer and the validator's messages.
        val retry = ai.prompts[3]
        assertTrue("# The validator refused it for these reasons" in retry)
        assertTrue("- block 2 (paragraph): the field \"text\" is missing" in retry, retry)
        assertTrue("block 3 (sources): the link \"https://invented.example.com/dotnet\" is not among the links" in retry, retry)

        val left = edition.leftOut.first { it.title == notes.title }
        assertEquals(notes.link, left.link)
        assertTrue(left.reason.startsWith("Ficou de fora: depois de 3 tentativas"), left.reason)
        assertTrue("the field \"text\" is missing" in left.reason, left.reason)
        assertEquals(edition.leftOut, store.editions.get(edition.key)!!.leftOut)
    }

    @Test
    fun theRequestsCarryTheRecipeTheSchemaAndThePublishedLinks() = runBlocking {
        store.marks.setInterestOff(magazine.id, "Rust", true)
        val seedEdition = store.editions.list(magazine.id).single()
        val liked = store.editions.get(seedEdition.key)!!.items.first()
        store.marks.setFeedback("${seedEdition.key}/${liked.id}", Feedback.MORE)
        val disliked = store.editions.get(seedEdition.key)!!.items.last()
        store.marks.setFeedback("${seedEdition.key}/${disliked.id}", Feedback.LESS)
        val pages = PageReader { url -> if (url == tool.link) "README do agent-guard: instale com dotnet tool install." else null }
        val ai = Recorded(listOf(selection, toolItem, noteItem))
        maker(ai, pages).make(magazine, run(listOf(tool, notes, bait)))

        val filter = ai.prompts[0]
        val write = ai.prompts[1]
        for (prompt in listOf(filter, write)) {
            magazine.topics.forEach { assertTrue(it.name in prompt, it.name) }
            assertTrue(magazine.prompt in prompt)
            assertTrue("Language of everything you write: Brazilian Portuguese (pt-BR)" in prompt)
            assertTrue("Tone: didatico (plain and didactic" in prompt)
            assertTrue("Interests the reader switched off (leave them out): Rust" in prompt)
            assertTrue(ItemRules.titleOf(liked.blocks)!! in prompt)
            assertTrue(ItemRules.titleOf(disliked.blocks)!! in prompt)
            // the links already published: every source link of the seed edition
            assertTrue("https://github.com/cloudflare/security-audit-skill" in prompt)
            assertTrue("https://github.com/trending?since=weekly" in prompt)
        }
        assertTrue("[r3] source: GitHub Trending" in filter && bait.rawText in filter)
        // the format's schema goes with the writing
        val schema = ItemFormat.schemaOf(magazine.format, magazine.name).toString()
        assertTrue(schema in write)
        assertTrue("https://json-schema.org/draft/2020-12/schema" in write)
        assertTrue("Accepted block types: illustration, title, summary" in write)
        // the primary page read for the item, and the addresses it may use
        assertTrue("## Primary page <${tool.link}>\nREADME do agent-guard" in write, write)
        assertTrue("- https://raw.example.org/acme/agent-guard/shot.png" in write)
        // the note's page could not be read: the prompt says so
        assertTrue("(it could not be read: say so in a note block" in ai.prompts[2])
        // JSON output is asked for on every call
        ai.transport.sent.forEach { assertTrue("\"response_format\":{\"type\":\"json_object\"}" in it.body!!) }
    }

    @Test
    fun editedPracticalOnlyInstructionsReachBothStepsAndExcludedPrimaryTextIsSkipped() = runBlocking {
        val edited = store.magazines.save(magazine.copy(
            prompt = "Escrever em Portugês (Brasil), no tom \"mais didático\". Só trazer ferramentas/sdk/skills/bibliotecas/MCP/serviços e afins.Não tenho interesse em palpites e artigos de opinião. O objetivo é encontrar ferramentas e serviços que otimizem meu trabalho no dia a dia. Ferramentas de trabalho novas com ou sem IA e que podem complementar ou se aproveitar ou não de IA. De preferência (mas não exclusivamente) feitos pela comunidade, gratuitos e de código aberto (não necessariamente todas essas coisas e dependendo mesmo que seja pago e código fechado pode ser interessante)",
        ))
        val opinion = notes.copy(title = "O futuro dos agentes", rawText = "Um editorial com previsões, sem ferramenta própria.")
        val ai = Recorded(listOf(selection, toolItem, """{"skip":"Editorial de opinião, excluído pelas instruções do leitor."}"""))
        val result = maker(ai).make(store.magazines.get(edited.id)!!, run(listOf(tool, opinion, bait)))
        val edition = assertNotNull(result.edition)
        assertEquals(listOf("agent-guard"), edition.items.map { it.id })
        assertEquals(2, edition.leftOut.size)
        assertTrue(edition.leftOut.any { it.title == opinion.title && it.reason.contains("Editorial de opinião") })
        assertTrue(ai.prompts.all { edited.prompt in it })
        assertTrue(ai.transport.sent.first().body!!.contains("eligibility rules"))
        assertTrue(ai.transport.sent[1].body!!.contains("excluded content"))
        assertEquals(edited.version, edition.recipeVersion)
        assertEquals(1, store.editions.get(edition.key)!!.items.size)
    }

    @Test
    fun aRecordAlreadyPublishedComesBackOnlyAsAnUpdateThatSaysWhatChanged() = runBlocking {
        // The AI forgets to call it an update: code makes it one, and the answer without "changed" goes back.
        val keepAgain = """{"keep": [{"records": ["r1"], "kind": "story", "update": false}], "leftOut": []}"""
        val updated = Samples.item("Auditoria de segurança", again.link, changed = "A versão 2 também audita workflows do GitHub Actions.")
        val ai = Recorded(listOf(keepAgain, Samples.item("Auditoria de segurança", again.link), updated))
        val result = maker(ai).make(magazine, run(emptyList(), candidates = listOf(again)))

        val filter = ai.prompts[0]
        assertTrue("already published: yes (only as an update that says what changed)" in filter)
        val write = ai.prompts[1]
        assertTrue("# This item is an update" in write)
        assertTrue("Uma auditoria de segurança em seis fases" in write, "the earlier item goes along")
        assertTrue("say what changed in the field \"changed\"" in ai.prompts[2])

        val item = assertNotNull(result.edition).items.single()
        assertTrue(ItemRules.isUpdate(item))
        assertTrue((item.blocks[0]["kicker"] as JsonPrimitive).content.startsWith("Atualização"))
        assertEquals("callout", item.blocks[1].type)
        assertEquals("A versão 2 também audita workflows do GitHub Actions.", (item.blocks[1]["text"] as JsonPrimitive).content)
    }

    @Test
    fun aRecordAlreadyPublishedWithNothingNewComesBackNotAtAll() = runBlocking {
        val keepAgain = """{"keep": [{"records": ["r1"], "update": true}], "leftOut": []}"""
        val ai = Recorded(listOf(keepAgain, """{"skip": "Nada mudou desde a edição 1."}"""))
        val result = maker(ai).make(magazine, run(emptyList(), candidates = listOf(again)))
        assertNull(result.edition)
        assertEquals(MakeProblem.NOTHING_WRITTEN, result.problem)
        assertEquals("Nada mudou desde a edição 1.", result.leftOut.single().reason)
        assertEquals(1, store.editions.list(magazine.id).size)
    }

    @Test
    fun noRecordsMeansNoCallAndNoEdition() = runBlocking {
        val ai = Recorded(emptyList())
        val result = maker(ai).make(magazine, run(emptyList()))
        assertEquals(MakeProblem.NOTHING_COLLECTED, result.problem)
        assertTrue(ai.transport.sent.isEmpty())
        assertEquals(1, store.editions.list(magazine.id).size)
    }

    @Test
    fun aSecondEditionOnTheSameDayGetsItsOwnKeyAndNumber() = runBlocking {
        maker(Recorded(listOf(selection, toolItem, noteItem))).make(magazine, run(listOf(tool, notes, bait)))
        // the first edition published the tool and the notes: only the third record is new now
        val keepBait = """{"keep": [{"records": ["r1"]}], "leftOut": []}"""
        val second = maker(Recorded(listOf(keepBait, Samples.item("Truques", bait.link)))).make(magazine, run(listOf(bait)))
        assertEquals("$today-2", second.edition!!.date)
        assertEquals(3, second.edition!!.number)
    }

    @Test
    fun thePipelineCollectsThenMakesAndReportsProgress() = runBlocking {
        val collector = object : MagazineCollector {
            override suspend fun collect(magazine: Magazine, onProgress: (WorkerProgress) -> Unit): WorkerResult {
                onProgress(WorkerProgress(0, 1, "GitHub Trending"))
                onProgress(WorkerProgress(1, 1, null))
                return run(listOf(tool, notes, bait))
            }
        }
        val seen = mutableListOf<MakeProgress>()
        val result = EditionPipeline(collector, maker(Recorded(listOf(selection, toolItem, noteItem)))).run(magazine) { seen += it }
        assertNotNull(result.edition)
        assertEquals(
            listOf(MakeStage.COLLECTING, MakeStage.CHOOSING, MakeStage.WRITING, MakeStage.SAVING),
            seen.map { it.stage }.distinct(),
        )
        assertTrue(seen.any { it.stage == MakeStage.WRITING && it.current == tool.title })
        assertFalse(seen.last().stage == MakeStage.COLLECTING)
    }

    @Test
    fun theReformatRewritesOnlyTheItemsOutsideTheCurrentFormatKeepingTheirLinks() = runBlocking {
        val key = store.editions.list(magazine.id).single().key
        val edition = store.editions.get(key)!!
        val noQuote = store.magazines.save(magazine.copy(format = magazine.format.without("quote")))
        val outside = edition.items.filter { !ItemFormat.check(it, noQuote.format).ok }
        assertTrue(outside.size >= 2)
        val first = outside[0]
        val firstLink = dobra.ai.rewrite.Verification.addressesOf(first).first.first()
        val answers = mutableListOf(Samples.item("Reescrita", firstLink))
        // the second item answers with an invented link three times and keeps its old text
        repeat(3) { answers += Samples.item("Inventada", "https://invented.example.com") }
        outside.drop(2).forEach { answers += """{"skip": "Sem como reescrever."}""" }
        val ai = Recorded(answers)
        val result = EditionReformatter(ai.gateway, ai.ledger, store.editions).reformat(noQuote, key)
        assertEquals(outside.size, result.outside)
        assertEquals(listOf(first.id), result.rewritten)
        val after = store.editions.get(key)!!
        val rewritten = after.items.first { it.id == first.id }
        assertTrue(ItemFormat.check(rewritten, noQuote.format).ok)
        assertEquals(first.kind, rewritten.kind)
        assertEquals(edition.items[edition.items.indexOf(outside[1])], after.items.first { it.id == outside[1].id })
        assertTrue("not among the links" in result.kept.first().reason)
        assertEquals(noQuote.version, after.recipeVersion)
        assertTrue("\"quote\"" !in ai.prompts[0].substringAfter("JSON Schema of one item").substringBefore("# The item to rewrite"))
        assertEquals(ai.transport.sent.size, ai.ledger.totalFor(key).calls)
    }
}
