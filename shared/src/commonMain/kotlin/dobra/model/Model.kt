package dobra.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON settings used everywhere in the app for reading and writing the model. */
val DobraJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// ---- item and edition ----

@Serializable
enum class ItemKind {
    @SerialName("story") STORY,
    @SerialName("note") NOTE,
}

/** A story or a short note: an ordered list of typed blocks. */
@Serializable
data class Item(val id: String, val kind: ItemKind, val blocks: List<Block>)

@Serializable
enum class EditionState {
    @SerialName("collected") COLLECTED,
    @SerialName("written") WRITTEN,
    @SerialName("validated") VALIDATED,
    @SerialName("published") PUBLISHED,
}

@Serializable
enum class CollectionState {
    @SerialName("answered") ANSWERED,
    @SerialName("partial") PARTIAL,
    @SerialName("failed") FAILED,
}

/** One line per source: records brought, state, note. */
@Serializable
data class CollectionLine(
    val source: String,
    val records: Int,
    val state: CollectionState,
    val note: String? = null,
)

/** Something considered and not included, with the reason. */
@Serializable
data class LeftOut(val title: String, val reason: String, val link: String? = null)

/** One issue. Its key is `<magazine>/<date>`. */
@Serializable
data class Edition(
    val magazine: String,
    val date: String,
    val number: Int,
    val recipeVersion: Int,
    val items: List<Item> = emptyList(),
    val collection: List<CollectionLine> = emptyList(),
    val leftOut: List<LeftOut> = emptyList(),
    val state: EditionState = EditionState.COLLECTED,
) {
    val key: String get() = "$magazine/$date"
}

// ---- looks ----

/** How one block type is drawn. Values are names, not raw styling. Every field is optional. */
@Serializable
data class Look(
    val font: String? = null,   // display, text, mono
    val size: String? = null,   // 0.88, 1.15, 1.3
    val color: String? = null,  // ink, ink-2, accent
    val align: String? = null,  // left, center, right, justify
)

/** Looks per block type. Stored apart from the content and never part of the schema. */
typealias Looks = Map<String, Look>

// ---- source ----

@Serializable
enum class SourceHealth {
    @SerialName("working") WORKING,
    @SerialName("repairing") REPAIRING,
    @SerialName("unavailable") UNAVAILABLE,
}

/** The collector script of a source. */
@Serializable
data class CollectorInfo(
    val script: String,
    val version: Int,
    val writtenAt: String,
    val model: String,
)

@Serializable
data class LastRun(val at: String, val records: Int, val state: CollectionState)

@Serializable
data class Source(
    val id: String,
    val name: String,
    val url: String,
    val liked: String = "",
    val collector: CollectorInfo? = null,
    val fixture: String? = null,
    val cursor: String? = null,
    val lastRun: LastRun? = null,
    val health: SourceHealth = SourceHealth.UNAVAILABLE,
)

// ---- magazine ----

@Serializable
data class Topic(val name: String, val weight: Double = 1.0)

@Serializable
enum class CadenceKind {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
}

@Serializable
data class Cadence(val kind: CadenceKind, val hour: Int, val weekday: Int? = null)

/** The format of a magazine: the accepted block types. `title` and `sources` are always accepted. */
@Serializable
data class Format(val accepted: List<String> = Blocks.ids) {
    /** The accepted types in standard order, with the required ones always included. */
    val types: List<String>
        get() = Blocks.all.filter { it.required || it.id in accepted }.map { it.id }

    fun accepts(type: String): Boolean = type in types

    fun without(type: String): Format = Format(types.filter { it != type })
}

/** The recipe of a magazine. */
@Serializable
data class Magazine(
    val id: String,
    val name: String,
    val about: String = "",
    val topics: List<Topic> = emptyList(),
    val keywords: List<String> = emptyList(),
    val language: String = "pt-BR",
    val tone: String = "",
    val prompt: String = "",
    val format: Format = Format(),
    val looks: Looks = emptyMap(),
    val cadence: Cadence = Cadence(CadenceKind.DAILY, 7),
    val sources: List<Source> = emptyList(),
    val version: Int = 1,
)
