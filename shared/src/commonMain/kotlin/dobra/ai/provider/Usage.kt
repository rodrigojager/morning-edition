package dobra.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One call made to a model: what it used and what it cost. [editionKey] is null for calls outside an edition. */
@Serializable
data class UsageRecord(
    val editionKey: String?,
    val job: AiJob,
    val providerId: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    /** In US dollars, from the prices the reader typed for the provider; zero when none was informed. */
    val costUsd: Double,
    val at: Long,
)

data class UsageTotal(val calls: Int, val inputTokens: Long, val outputTokens: Long, val costUsd: Double)

/** The record of every call, saved in `usage.json` (it holds no key and no text of any request). */
class UsageLedger(private val storage: BlobStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(UsageRecord.serializer())
    private var records: List<UsageRecord> =
        storage.read(FILE)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    fun record(entry: UsageRecord) {
        records = records + entry
        storage.write(FILE, json.encodeToString(serializer, records))
    }

    fun all(): List<UsageRecord> = records

    fun totalFor(editionKey: String): UsageTotal = total(records.filter { it.editionKey == editionKey })

    private fun total(list: List<UsageRecord>) = UsageTotal(
        calls = list.size,
        inputTokens = list.sumOf { it.inputTokens },
        outputTokens = list.sumOf { it.outputTokens },
        costUsd = list.sumOf { it.costUsd },
    )

    companion object {
        const val FILE = "usage.json"
    }
}
