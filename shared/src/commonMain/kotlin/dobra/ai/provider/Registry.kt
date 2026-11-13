package dobra.ai.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** Small named text blobs kept apart from the reader's store (the app uses files in its private folder). */
interface BlobStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
}

class MemoryBlobStorage : BlobStorage {
    val blobs = mutableMapOf<String, String>()
    override fun read(name: String) = blobs[name]
    override fun write(name: String, text: String) { blobs[name] = text }
    override fun delete(name: String) { blobs.remove(name) }
}

/** Encrypts secrets. The app's implementation uses a key that never leaves the system's keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

@Serializable
data class AiSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val choices: Map<AiJob, JobChoice> = emptyMap(),
)

/**
 * The providers the reader added and the choice of each job. Everything but the keys is saved in `providers.json`;
 * a key is saved only as ciphertext in its own blob, and read back only to make a call.
 */
class ProviderRegistry(private val storage: BlobStorage, private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AiSettings> = _state

    private fun load(): AiSettings =
        storage.read(SETTINGS)?.let { runCatching { json.decodeFromString<AiSettings>(it) }.getOrNull() } ?: AiSettings()

    private fun save(next: AiSettings) {
        storage.write(SETTINGS, json.encodeToString(AiSettings.serializer(), next))
        _state.value = next
    }

    fun add(
        name: String,
        kind: ProviderKind,
        baseUrl: String,
        key: String,
        inputPricePerMillion: Double = 0.0,
        outputPricePerMillion: Double = 0.0,
    ): ProviderConfig {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty() && name.isNotBlank() && baseUrl.isNotBlank()) { "name, address and key are required" }
        val current = _state.value
        val id = "p" + ((current.providers.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        val config = ProviderConfig(
            id = id,
            name = name.trim(),
            kind = kind,
            baseUrl = baseUrl.trim(),
            keyLast4 = trimmed.takeLast(4),
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
        )
        storage.write(keyBlob(id), Base64.encode(cipher.encrypt(trimmed.encodeToByteArray())))
        save(current.copy(providers = current.providers + config))
        return config
    }

    /** Registers the ChatGPT plan connection without an API-key blob. */
    fun addChatGpt(): ProviderConfig {
        val current = _state.value
        current.providers.firstOrNull { it.kind == ProviderKind.CHATGPT_OAUTH }?.let { return it }
        val config = ProviderConfig("chatgpt", "ChatGPT", ProviderKind.CHATGPT_OAUTH, "https://api.openai.com/v1", "")
        save(current.copy(providers = current.providers + config))
        return config
    }

    fun remove(id: String) {
        storage.delete(keyBlob(id))
        val current = _state.value
        save(
            current.copy(
                providers = current.providers.filterNot { it.id == id },
                choices = current.choices.filterValues { it.providerId != id },
            ),
        )
    }

    fun choose(job: AiJob, choice: JobChoice?) {
        val current = _state.value
        save(current.copy(choices = if (choice == null) current.choices - job else current.choices + (job to choice)))
    }

    /** A model and its options belong to one provider; never carry them to a different account. */
    fun selectProvider(job: AiJob, providerId: String) {
        if (_state.value.choices[job]?.providerId == providerId) return
        if (_state.value.providers.none { it.id == providerId }) return
        choose(job, JobChoice(providerId, "", Effort.AUTO))
    }

    fun selectModel(job: AiJob, model: String) {
        val choice = _state.value.choices[job] ?: return
        if (choice.model == model) return
        if (_state.value.providers.none { it.id == choice.providerId }) return
        choose(job, choice.copy(model = model, effort = Effort.AUTO, serviceTier = null,
            effortMode = EffortMode.THINKING_BUDGET))
    }

    fun selectModel(job: AiJob, model: ModelOption) {
        val choice = _state.value.choices[job] ?: return
        if (choice.model == model.id && choice.effortMode == model.effortMode) return
        choose(job, choice.copy(model = model.id, effort = Effort.AUTO, serviceTier = null, effortMode = model.effortMode))
    }

    fun applyToAllJobs(job: AiJob) {
        val current = _state.value
        val choice = current.choices[job]?.takeIf { it.model.isNotBlank() } ?: return
        save(current.copy(choices = AiJob.entries.associateWith { choice }))
    }

    /** The plain key, for making a call. Null when the provider is unknown. */
    fun key(id: String): String? =
        storage.read(keyBlob(id))?.let { cipher.decrypt(Base64.decode(it)).decodeToString() }

    private fun keyBlob(id: String) = "key-$id.enc"

    companion object {
        const val SETTINGS = "providers.json"
    }
}
