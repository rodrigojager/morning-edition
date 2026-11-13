package dobra.collect.engine

import dobra.model.CollectionState
import kotlinx.serialization.Serializable

/** What a collector receives (PLAN-collectors, "The collector contract"). */
data class CollectorInput(
    /** The source address. The collector may fetch only from its domain (see [SourceDomain]). */
    val url: String,
    /** Optional prefilter: keep records containing a keyword; an empty list keeps every valid record. */
    val keywords: List<String>,
    /** Where the last run stopped, as the collector returned it; null on the first run. */
    val cursor: String? = null,
)

/**
 * A raw record: `{ title, link, date, rawText, signal?, media? }`. `date` is the site's own date text ("" when the
 * site gives none), `signal` the site's own measure of attention, `media` image or video addresses beside the text.
 */
@Serializable
data class RawRecord(
    val title: String,
    val link: String,
    val date: String,
    val rawText: String,
    val signal: Double? = null,
    val media: List<String> = emptyList(),
)

/** One record the collector returned that failed the record shape, with the reason. Never used further. */
data class RefusedRecord(val index: Int, val reason: String)

/** One fetch the collector made: the address and the status (0 when the transport failed). */
data class FetchLog(val url: String, val status: Int, val note: String? = null)

enum class FailureKind {
    /** The script is not erasable TypeScript or does not parse. */
    SYNTAX,

    /** The script threw, or returned nothing. */
    SCRIPT_ERROR,

    /** The script asked for an address outside its source's domain, or too many addresses. */
    FORBIDDEN_FETCH,

    /** The run went past the time limit. */
    TIMEOUT,

    /** The run went past the memory limit. */
    MEMORY,

    /** The result is larger than allowed (characters or records). */
    OUTPUT_TOO_LARGE,

    /** The result is not `{ records: [...], cursor }` plain data. */
    BAD_OUTPUT,

    /** The input itself is unusable (for example the source address is not http or https). */
    BAD_INPUT,
}

data class CollectorFailure(val kind: FailureKind, val reason: String)

/** Limits of one run. Defaults suit a phone; tests lower them. */
data class CollectorLimits(
    val timeMillis: Long = 20_000,
    val memoryBytes: Long = 64L * 1024 * 1024,
    val stackBytes: Long = 1024L * 1024,
    /** Characters of the JSON result. */
    val maxOutputChars: Int = 2_000_000,
    val maxRecords: Int = 500,
    val maxFetches: Int = 30,
    /** Characters of one fetched body. */
    val maxResponseChars: Int = 5_000_000,
)

/**
 * The result of one run. [wellFormed] are the records that passed the record shape; [kept] are those of them that
 * contain a keyword. A failed run has no records and keeps the reason in [failure].
 */
data class CollectorRun(
    val state: CollectionState,
    val wellFormed: List<RawRecord>,
    val kept: List<RawRecord>,
    /** How many records the script returned, before any check. */
    val returned: Int,
    val refused: List<RefusedRecord>,
    /** Kept records per keyword, in the order of the keywords. */
    val perKeyword: Map<String, Int>,
    val cursor: String?,
    val fetches: List<FetchLog>,
    val failure: CollectorFailure? = null,
) {
    companion object {
        fun failed(kind: FailureKind, reason: String, cursor: String?, fetches: List<FetchLog> = emptyList()) =
            CollectorRun(
                state = CollectionState.FAILED,
                wellFormed = emptyList(),
                kept = emptyList(),
                returned = 0,
                refused = emptyList(),
                perKeyword = emptyMap(),
                cursor = cursor,
                fetches = fetches,
                failure = CollectorFailure(kind, reason),
            )
    }
}

/** A response of the real transport (or of a saved page). */
data class FetchResponse(
    val status: Int,
    val body: String,
    val contentType: String? = null,
    /** The address after redirects, when the transport followed any. */
    val finalUrl: String? = null,
)

/**
 * The way out of the engine. The app gives the real network transport; tests give saved pages. The engine has
 * already checked the address against the source's domain before calling it, and checks [FetchResponse.finalUrl]
 * after.
 */
fun interface FetchTransport {
    suspend fun get(url: String): FetchResponse
}

/** Serves saved pages by address: the fixture of a source, or the pages of a test. Unknown addresses answer 404. */
class SavedPages(private val pages: Map<String, FetchResponse>) : FetchTransport {
    override suspend fun get(url: String): FetchResponse =
        pages[url] ?: FetchResponse(404, "", finalUrl = url)
}
