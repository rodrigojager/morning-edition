package dobra.collect.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import dobra.model.CollectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Runs a collector: a TypeScript script for one source, isolated in its own QuickJS runtime (see AGENTS.md,
 * "Collector engine").
 *
 * The script defines `collect(input)` (it may be `async`), receives `{ url, keywords, cursor }` and returns
 * `{ records: [...], cursor }`. Its only way out is `fetch`, which reaches only its source's domain through
 * [transport]. Each run gets a fresh runtime with limits of time, memory and returned size; the result is
 * checked against the record shape and filtered by the keywords before anything uses it.
 */
class CollectorEngine(
    private val transport: FetchTransport,
    private val limits: CollectorLimits = CollectorLimits(),
) {
    suspend fun run(script: String, input: CollectorInput): CollectorRun {
        val source = WebAddress.parse(input.url) ?: return CollectorRun.failed(
            FailureKind.BAD_INPUT, "the source address is not an http or https address: ${input.url}", input.cursor,
        )
        val javascript = try {
            TypeStripper.strip(script)
        } catch (e: TypeScriptSyntaxException) {
            return CollectorRun.failed(FailureKind.SYNTAX, e.message ?: e.reason, input.cursor)
        }
        val session = RunSession(SourceDomain(source), transport, limits)
        val engineFailure = withContext(Dispatchers.Default) { evaluate(javascript, input, session) }
        val fetches = session.fetchLog()

        session.violation?.let { return CollectorRun.failed(FailureKind.FORBIDDEN_FETCH, it, input.cursor, fetches) }
        engineFailure?.let { return CollectorRun.failed(it.kind, it.reason, input.cursor, fetches) }
        session.scriptFailure?.let {
            val failure = if (isOutOfMemory(it)) memory() else CollectorFailure(FailureKind.SCRIPT_ERROR, it)
            return CollectorRun.failed(failure.kind, failure.reason, input.cursor, fetches)
        }
        session.oversized?.let {
            return CollectorRun.failed(
                FailureKind.OUTPUT_TOO_LARGE,
                "the result has $it characters; the limit is ${limits.maxOutputChars}",
                input.cursor,
                fetches,
            )
        }
        val json = session.result ?: return CollectorRun.failed(
            FailureKind.SCRIPT_ERROR, "collect(input) never finished: its promise did not settle", input.cursor, fetches,
        )
        return readResult(json, input, fetches)
    }

    private suspend fun evaluate(javascript: String, input: CollectorInput, session: RunSession): CollectorFailure? {
        val quickJs = QuickJs.create(Dispatchers.Default)
        return try {
            quickJs.memoryLimit = limits.memoryBytes
            quickJs.maxStackSize = limits.stackBytes
            quickJs.defineBinding("__dobraFetch", AsyncFunctionBinding { args -> session.fetch(args.getOrNull(0)) })
            quickJs.defineBinding("__dobraDone", FunctionBinding { args -> session.done(args.getOrNull(0)) })
            quickJs.defineBinding("__dobraFail", FunctionBinding { args -> session.fail(args.getOrNull(0)) })
            withTimeout(limits.timeMillis) {
                quickJs.evaluate<Any?>(PRELUDE, "prelude.js")
                quickJs.evaluate<Any?>(javascript, "collector.js")
                quickJs.evaluate<Any?>("__dobraRun(${inputJson(input)});", "run.js")
            }
            null
        } catch (e: TimeoutCancellationException) {
            timeout()
        } catch (e: QuickJsException) {
            val message = e.message ?: e.toString()
            when {
                e::class.simpleName == "QuickJsInterruptedException" -> timeout()
                isOutOfMemory(message) -> memory()
                message.startsWith("SyntaxError") -> CollectorFailure(FailureKind.SYNTAX, message)
                else -> CollectorFailure(FailureKind.SCRIPT_ERROR, message)
            }
        } finally {
            quickJs.close()
        }
    }

    /** QuickJS reports an exhausted memory limit as `InternalError: out of memory` (thrown or as a rejection). */
    private fun isOutOfMemory(message: String) = message.startsWith("InternalError: out of memory") ||
        message.startsWith("out of memory")

    private fun memory() = CollectorFailure(FailureKind.MEMORY, "went past the memory limit of ${limits.memoryBytes} bytes")

    private fun timeout() =CollectorFailure(FailureKind.TIMEOUT, "stopped at the time limit of ${limits.timeMillis} ms")

    private fun inputJson(input: CollectorInput): String = buildJsonObject {
        put("url", input.url)
        putJsonArray("keywords") { input.keywords.forEach { add(JsonPrimitive(it)) } }
        put("cursor", input.cursor)
    }.toString()

    private fun readResult(json: String, input: CollectorInput, fetches: List<FetchLog>): CollectorRun {
        fun bad(reason: String) = CollectorRun.failed(FailureKind.BAD_OUTPUT, reason, input.cursor, fetches)
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return bad("the result is not JSON: ${e.message}")
        }
        if (root !is JsonObject) return bad("collect(input) must return { records: [...], cursor }")
        val records = root["records"] as? JsonArray ?: return bad("the result has no records array")
        if (records.size > limits.maxRecords) {
            return CollectorRun.failed(
                FailureKind.OUTPUT_TOO_LARGE,
                "the result has ${records.size} records; the limit is ${limits.maxRecords}",
                input.cursor,
                fetches,
            )
        }
        // No cursor in the result keeps the previous one; null resets it.
        val c = root["cursor"]
        val cursor = when {
            c == null -> input.cursor
            c is JsonNull -> null
            c is JsonPrimitive && c.isString && c.content.length <= MAX_CURSOR -> c.content
            else -> return bad("the cursor must be a string of at most $MAX_CURSOR characters, or null")
        }

        val wellFormed = ArrayList<RawRecord>()
        val refused = ArrayList<RefusedRecord>()
        records.forEachIndexed { index, element ->
            when (val checked = RecordShape.check(element)) {
                is RecordShape.Checked.Ok -> wellFormed.add(checked.record)
                is RecordShape.Checked.Refused -> refused.add(RefusedRecord(index, checked.reason))
            }
        }
        val perKeyword = LinkedHashMap<String, Int>().apply { input.keywords.forEach { put(it, 0) } }
        val kept = wellFormed.filter { record ->
            val found = KeywordMatcher.matching(input.keywords, record.title, record.rawText)
            found.forEach { perKeyword[it] = (perKeyword[it] ?: 0) + 1 }
            input.keywords.isEmpty() || found.isNotEmpty()
        }
        val fetchTrouble = fetches.any { it.status !in 200..399 }
        return CollectorRun(
            state = if (refused.isNotEmpty() || fetchTrouble) CollectionState.PARTIAL else CollectionState.ANSWERED,
            wellFormed = wellFormed,
            kept = kept,
            returned = records.size,
            refused = refused,
            perKeyword = perKeyword,
            cursor = cursor,
            fetches = fetches,
        )
    }

    private companion object {
        const val MAX_CURSOR = 4096
    }
}

/** The record shape: `{ title, link, date, rawText, signal?, media? }`. */
internal object RecordShape {
    sealed interface Checked {
        data class Ok(val record: RawRecord) : Checked
        data class Refused(val reason: String) : Checked
    }

    fun check(element: JsonElement): Checked {
        val o = element as? JsonObject ?: return Checked.Refused("a record must be an object")
        val title = o.string("title")?.trim()
        if (title.isNullOrEmpty()) return Checked.Refused("a record needs a title")
        val link = o.string("link")?.trim()
        if (link.isNullOrEmpty()) return Checked.Refused("a record needs a link")
        if (WebAddress.parse(link) == null) return Checked.Refused("the link is not an http or https address: $link")
        val date = o.string("date") ?: return Checked.Refused("a record needs a date text (\"\" when the site has none)")
        val rawText = o.string("rawText") ?: return Checked.Refused("a record needs rawText (\"\" when there is none)")
        val signal = when (val s = o["signal"]) {
            null, is JsonNull -> null
            is JsonPrimitive -> if (s.isString) null else s.doubleOrNull?.takeIf { it.isFinite() }
            else -> null
        }
        if (o["signal"].let { it != null && it !is JsonNull } && signal == null) {
            return Checked.Refused("signal must be a number")
        }
        val media = when (val m = o["media"]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> m.map { item ->
                val address = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                if (address == null || WebAddress.parse(address) == null) {
                    return Checked.Refused("media must list http or https addresses")
                }
                address
            }
            else -> return Checked.Refused("media must be a list of addresses")
        }
        return Checked.Ok(RawRecord(title, link, date, rawText, signal, media))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** What one run's host functions see and record. */
private class RunSession(
    private val domain: SourceDomain,
    private val transport: FetchTransport,
    private val limits: CollectorLimits,
) {
    private val mutex = Mutex()
    private val fetches = ArrayList<FetchLog>()
    private var count = 0

    /** The first forbidden request; the run fails with it even if the script caught the error. */
    var violation: String? = null
        private set
    var result: String? = null
        private set
    var scriptFailure: String? = null
        private set
    var oversized: Int? = null
        private set

    suspend fun fetchLog(): List<FetchLog> = mutex.withLock { fetches.toList() }

    fun done(arg: Any?) {
        val json = arg as? String ?: "null"
        if (json.length > limits.maxOutputChars) oversized = json.length else result = json
    }

    fun fail(arg: Any?) {
        scriptFailure = (arg as? String) ?: "the script failed"
    }

    suspend fun fetch(arg: Any?): String {
        val raw = arg as? String ?: return error("fetch needs an address")
        val tooMany = mutex.withLock { ++count > limits.maxFetches }
        if (tooMany) return refuse(raw, "more than ${limits.maxFetches} fetches in one run")
        val target = domain.source.resolve(raw) ?: return refuse(raw, "not an http or https address: $raw")
        if (!domain.allows(target)) {
            return refuse(target.toString(), "$target is outside the source's domain (${domain.domain})")
        }
        val response = try {
            transport.get(target.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(FetchLog(target.toString(), 0, e.message))
            return error("fetch failed for $target: ${e.message ?: e::class.simpleName}")
        }
        val final = response.finalUrl?.let { WebAddress.parse(it) ?: return refuse(it, "redirected to $it") } ?: target
        if (!domain.allows(final)) {
            return refuse(final.toString(), "$target redirected outside the source's domain to $final")
        }
        if (response.body.length > limits.maxResponseChars) {
            log(FetchLog(target.toString(), response.status, "response too large"))
            return error("the response of $target has more than ${limits.maxResponseChars} characters")
        }
        log(FetchLog(target.toString(), response.status))
        return buildJsonObject {
            put("status", response.status)
            put("url", final.toString())
            put("contentType", response.contentType)
            put("body", response.body)
        }.toString()
    }

    private suspend fun refuse(url: String, reason: String): String {
        mutex.withLock {
            if (violation == null) violation = "refused fetch: $reason"
            fetches.add(FetchLog(url, 0, "refused: $reason"))
        }
        return error(reason)
    }

    private suspend fun log(entry: FetchLog) = mutex.withLock { fetches.add(entry) }

    private fun error(reason: String): String = buildJsonObject { put("error", reason) }.toString()
}
