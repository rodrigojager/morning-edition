package dobra.ai.ask

import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.JobChoice
import dobra.ai.provider.MemoryBlobStorage
import dobra.ai.provider.ProviderKind
import dobra.ai.provider.ProviderRegistry
import dobra.ai.provider.ReplayTransport
import dobra.ai.provider.ReverseCipher
import dobra.ai.provider.UsageLedger
import dobra.ai.provider.reply
import dobra.model.Block
import dobra.model.Item
import dobra.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AskTest {
    private fun text(vararg pairs: Pair<String, String>) = pairs.associate { it.first to JsonPrimitive(it.second) }

    private val item = Item(
        "s1", ItemKind.STORY,
        listOf(
            Block("title", text("kicker" to "CIÊNCIA", "text" to "O gelo que some")),
            Block("paragraph", text("text" to "A calota perdeu 270 bilhões de toneladas por ano.")),
            Block(
                "sources",
                mapOf(
                    "links" to JsonArray(
                        listOf(
                            JsonObject(text("label" to "Relatório anual", "url" to "https://fonte.test/relatorio")),
                            JsonObject(text("label" to "Dados brutos", "url" to "https://fonte.test/dados")),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val openAiAnswer = """{"id":"c1","object":"chat.completion","created":1,"model":"m",
        "choices":[{"index":0,"message":{"role":"assistant","content":"Perdeu muito gelo."},"finish_reason":"stop"}],
        "usage":{"prompt_tokens":1000000,"completion_tokens":500000,"total_tokens":1500000}}"""

    @Test
    fun requestCarriesQuestionFullTextAndLinksAndNothingAboutTheReader() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://x.test", "sk-fake-0000-ABCD", 2.0, 4.0)
        registry.choose(AiJob.ANSWER, JobChoice(p.id, "gpt-x"))
        val ledger = UsageLedger(storage)
        val transport = ReplayTransport(mutableListOf(reply(200, openAiAnswer)))
        val service = AskService(AiGateway(registry, transport, ledger), ledger, ConversationStore(storage))

        val conversation = service.ask("m1/e1/s1", item, "Quanto gelo foi perdido?")

        val body = transport.sent.single().body!!
        assertTrue("Quanto gelo foi perdido?" in body)
        assertTrue("A calota perdeu 270 bilh" in body)
        assertTrue("O gelo que some" in body)
        assertTrue("https://fonte.test/relatorio" in body)
        assertTrue("https://fonte.test/dados" in body)
        // Nothing of the reader: no notes, no feedback, no marks.
        for (word in listOf("nota do leitor", "feedback", "saved", "salvo")) assertFalse(word in body.lowercase(), word)

        assertEquals(2, conversation.messages.size)
        val answer = conversation.messages.last()
        assertEquals("Perdeu muito gelo.", answer.text)
        assertEquals(2.0 + 2.0, answer.costUsd, 1e-9)
        assertEquals(2, conversation.messages.first().linkCount)
        // The conversation is kept with the story and survives a new store over the same storage.
        assertEquals(conversation, ConversationStore(storage).load("m1/e1/s1"))
        assertTrue(ConversationStore(storage).load("other").messages.isEmpty())
    }

    @Test
    fun secondQuestionCarriesTheConversationSoFar() = runTest {
        val storage = MemoryBlobStorage()
        val registry = ProviderRegistry(storage, ReverseCipher())
        val p = registry.add("OpenAI", ProviderKind.OPENAI_STYLE, "https://x.test", "sk-fake-0000-ABCD")
        registry.choose(AiJob.ANSWER, JobChoice(p.id, "gpt-x"))
        val ledger = UsageLedger(storage)
        val transport = ReplayTransport(mutableListOf(reply(200, openAiAnswer), reply(200, openAiAnswer)))
        val service = AskService(AiGateway(registry, transport, ledger), ledger, ConversationStore(storage))
        service.ask("k", item, "Primeira?")
        service.ask("k", item, "Segunda?")
        val second = transport.sent.last().body!!
        assertTrue("Primeira?" in second && "Perdeu muito gelo." in second && "Segunda?" in second)
    }
}
