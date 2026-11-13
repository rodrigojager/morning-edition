package dobra.ai.ask

import dobra.ai.provider.AiGateway
import dobra.ai.provider.AiJob
import dobra.ai.provider.AiRequest
import dobra.ai.provider.BlobStorage
import dobra.ai.provider.UsageLedger
import dobra.model.Block
import dobra.model.Item
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One source link of a story, as it goes along with the question. */
@Serializable
data class SourceLink(val label: String, val url: String)

/** What goes along with a question: the full text of the story and its source links. Nothing about the reader. */
@Serializable
data class StoryReference(val text: String, val links: List<SourceLink>) {
    /** Characters of the reference as sent (text plus the links block). */
    val size: Int get() = render().length

    fun render(): String = buildString {
        append(text)
        if (links.isNotEmpty()) {
            append("\n\nLINKS DAS FONTES:\n")
            links.forEach { append("- ").append(it.label).append(" <").append(it.url).append(">\n") }
        }
    }.trimEnd()

    companion object {
        /** Reads the story's blocks: every string they hold, in order, and every link of its sources. */
        fun of(item: Item): StoryReference {
            val links = mutableListOf<SourceLink>()
            val text = item.blocks.joinToString("\n\n") { block ->
                collectLinks(block, links)
                val lines = mutableListOf<String>()
                block.fields.forEach { (key, value) -> if (key !in SKIPPED) flatten(value, lines) }
                lines.joinToString("\n")
            }.trim()
            return StoryReference(text, links.distinctBy { it.url })
        }

        private val SKIPPED = setOf("art", "src", "href", "url", "links")

        private fun flatten(e: JsonElement, out: MutableList<String>) {
            when (e) {
                is JsonPrimitive -> if (e.isString && e.content.isNotBlank()) out += e.content
                is JsonArray -> e.forEach { flatten(it, out) }
                is JsonObject -> e.forEach { (k, v) -> if (k !in SKIPPED) flatten(v, out) }
            }
        }

        private fun collectLinks(block: Block, out: MutableList<SourceLink>) {
            fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (block.type) {
                "sources" -> (block["links"] as? JsonArray)?.forEach { l ->
                    val o = l as? JsonObject ?: return@forEach
                    val url = str(o, "url") ?: return@forEach
                    out += SourceLink(str(o, "label") ?: url, url)
                }
                "quote" -> block.fields["url"]?.let { u ->
                    (u as? JsonPrimitive)?.takeIf { it.isString }?.let {
                        out += SourceLink((block["who"] as? JsonPrimitive)?.content ?: it.content, it.content)
                    }
                }
            }
        }
    }
}

/** One message of a conversation. The answer turns carry what the call used and cost. */
@Serializable
data class AskMessage(
    val fromReader: Boolean,
    val text: String,
    val referenceChars: Int = 0,
    val linkCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val model: String = "",
)

@Serializable
data class Conversation(val messages: List<AskMessage> = emptyList())

/** The conversations of the stories, one file per story, kept apart from the store. */
class ConversationStore(private val storage: BlobStorage) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(itemKey: String): Conversation =
        storage.read(file(itemKey))?.let { runCatching { json.decodeFromString(Conversation.serializer(), it) }.getOrNull() }
            ?: Conversation()

    fun save(itemKey: String, conversation: Conversation) {
        storage.write(file(itemKey), json.encodeToString(Conversation.serializer(), conversation))
    }

    private fun file(itemKey: String) =
        "ask-" + itemKey.map { if (it.isLetterOrDigit() || it == '-') it.toString() else "_" + it.code.toString(16) }.joinToString("") + ".json"
}

/** Builds the request and runs a question about one story through the gateway. */
class AskService(
    private val gateway: AiGateway,
    private val ledger: UsageLedger,
    private val conversations: ConversationStore,
) {
    /** Sends [question] about [item]; the new messages are saved with the story and the updated conversation returned. */
    suspend fun ask(itemKey: String, item: Item, question: String): Conversation {
        val before = conversations.load(itemKey)
        val reference = StoryReference.of(item)
        val answer = gateway.call(AiJob.ANSWER, request(reference, before, question.trim()))
        val record = ledger.all().last()
        val after = Conversation(
            before.messages + listOf(
                AskMessage(true, question.trim(), referenceChars = reference.size, linkCount = reference.links.size),
                AskMessage(
                    false, answer.text.trim(),
                    inputTokens = answer.usage.inputTokens, outputTokens = answer.usage.outputTokens,
                    costUsd = record.costUsd, model = record.model,
                ),
            ),
        )
        conversations.save(itemKey, after)
        return after
    }

    companion object {
        const val SYSTEM = "Você responde perguntas de um leitor sobre uma matéria de uma revista pessoal. " +
            "Responda em português do Brasil, de forma simples e didática. Use apenas o texto da matéria e os links " +
            "das fontes dados como referência; não invente nada. Se a referência não permitir responder, diga isso " +
            "claramente e separe o que seria apenas suposição."

        /** The request without model and effort: the gateway fills those from the job's choice. */
        fun request(reference: StoryReference, conversation: Conversation, question: String): AiRequest {
            val user = buildString {
                append("MATÉRIA (referência):\n").append(reference.render())
                if (conversation.messages.isNotEmpty()) {
                    append("\n\nCONVERSA ATÉ AQUI:\n")
                    conversation.messages.forEach {
                        append(if (it.fromReader) "Pergunta: " else "Resposta: ").append(it.text).append('\n')
                    }
                }
                append("\n\nPERGUNTA:\n").append(question)
            }
            return AiRequest(model = "", user = user, system = SYSTEM)
        }
    }
}
