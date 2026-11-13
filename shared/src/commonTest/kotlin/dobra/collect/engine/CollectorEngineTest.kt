package dobra.collect.engine

import dobra.model.CollectionState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The sandbox, on every target that runs commonTest (`:shared:jvmTest` and `:shared:testAndroidHostTest`).
 * No test touches the network: the transport serves pages written here.
 */
class CollectorEngineTest {
    private val source = "https://www.example.org/"
    private val apiUrl = "https://api.example.org/items.json"
    private val apiPage = FetchResponse(
        200,
        """
        [{"t":"Falha de segurança no roteador","u":"https://www.example.org/a/1","d":"2026-11-12","x":"Atualize já.","p":12},
         {"t":"Receita de bolo","u":"https://www.example.org/a/2","d":"2026-11-11","x":"Farinha e ovos.","p":3},
         {"t":"Kotlin e tipos","u":"https://www.example.org/a/3","d":"2026-11-10","x":"Mais SEGURANCA com null safety.","p":40}]
        """.trimIndent(),
        "application/json",
    )
    private val pages = mapOf(apiUrl to apiPage)
    private val keywords = listOf("segurança")

    private fun engine(
        limits: CollectorLimits = CollectorLimits(timeMillis = 5_000),
        transport: FetchTransport = SavedPages(pages),
    ) = CollectorEngine(transport, limits)

    private suspend fun run(script: String, limits: CollectorLimits = CollectorLimits(timeMillis = 5_000)) =
        engine(limits).run(script, CollectorInput(source, keywords, null))

    /** A collector returning the given JavaScript array expression as its records. */
    private fun returning(records: String) = "async function collect(input) { return { records: $records, cursor: null }; }"

    private val typedCollector = """
        interface ApiItem { t: string; u: string; d: string; x: string; p?: number }
        interface Out { title: string; link: string; date: string; rawText: string; signal?: number }
        type Result = { records: Out[]; cursor: string | null };

        function toRecord(item: ApiItem): Out {
          return { title: item.t, link: item.u, date: item.d, rawText: item.x, signal: item.p };
        }

        async function collect(input: { url: string; keywords: string[]; cursor: string | null }): Promise<Result> {
          const response = await fetch("https://api.example.org/items.json");
          const items = (await response.json()) as ApiItem[];
          const records: Out[] = items.map((i: ApiItem): Out => toRecord(i));
          return { records, cursor: items.length > 0 ? items[0]!.u : input.cursor };
        }
    """.trimIndent()

    @Test
    fun aCollectorWithTypeScriptAnnotationsRuns() = runTest {
        val run = run(typedCollector)
        assertNull(run.failure, run.failure?.reason)
        assertEquals(CollectionState.ANSWERED, run.state)
        assertEquals(3, run.returned)
        assertEquals(3, run.wellFormed.size)
        assertEquals(
            RawRecord("Falha de segurança no roteador", "https://www.example.org/a/1", "2026-11-12", "Atualize já.", 12.0),
            run.wellFormed[0],
        )
        assertEquals("https://www.example.org/a/1", run.cursor)
        assertEquals(listOf(FetchLog(apiUrl, 200)), run.fetches)
    }

    @Test
    fun theSameSavedPageGivesTheSameRecords() = runTest {
        val first = run(typedCollector)
        val second = run(typedCollector)
        assertEquals(first, second)
        assertEquals(3, first.wellFormed.size)
    }

    @Test
    fun onlyRecordsWithAKeywordAreKept() = runTest {
        val run = run(typedCollector)
        // "segurança" matches the first title and, without accent and in capitals, the third text.
        assertEquals(listOf("https://www.example.org/a/1", "https://www.example.org/a/3"), run.kept.map { it.link })
        assertEquals(mapOf("segurança" to 2), run.perKeyword)
        val none = engine().run(typedCollector, CollectorInput(source, emptyList(), null))
        assertEquals(none.wellFormed, none.kept, "without a keyword prefilter the AI can curate every valid record")
        assertEquals(3, none.wellFormed.size)
    }

    @Test
    fun anAddressOutsideTheSourceDomainIsRefusedAndFailsTheRun() = runTest {
        val caught = run(
            """
            async function collect(input) {
              let message = "";
              try { await fetch("https://tracker.example.net/collect"); } catch (e) { message = e.name + ": " + e.message; }
              return { records: [{ title: message, link: "https://www.example.org/x", date: "", rawText: "" }] };
            }
            """.trimIndent(),
        )
        assertEquals(CollectionState.FAILED, caught.state)
        assertEquals(FailureKind.FORBIDDEN_FETCH, caught.failure?.kind)
        assertTrue("tracker.example.net" in caught.failure!!.reason, caught.failure.reason)
        assertTrue("outside the source's domain" in caught.failure.reason)
        assertTrue(caught.kept.isEmpty() && caught.wellFormed.isEmpty(), "a failed run hands nothing on")
        assertEquals(0, caught.fetches.single().status)

        val uncaught = run("async function collect() { await fetch('https://example.org.evil.net/'); return { records: [] }; }")
        assertEquals(FailureKind.FORBIDDEN_FETCH, uncaught.failure?.kind)

        val withUser = run("async function collect() { await fetch('https://www.example.org@evil.net/'); return { records: [] }; }")
        assertEquals(FailureKind.FORBIDDEN_FETCH, withUser.failure?.kind)

        val otherScheme = run("async function collect() { await fetch('file:///etc/passwd'); return { records: [] }; }")
        assertEquals(FailureKind.FORBIDDEN_FETCH, otherScheme.failure?.kind)
    }

    @Test
    fun theSourceDomainAndItsSubdomainsAreAllowedAndRelativeAddressesResolve() = runTest {
        val transport = SavedPages(
            mapOf(
                "https://www.example.org/feed" to FetchResponse(200, "a"),
                "https://example.org/b" to FetchResponse(200, "b"),
                apiUrl to FetchResponse(200, "c"),
            ),
        )
        val run = engine(transport = transport).run(
            """
            async function collect() {
              const parts = [];
              for (const a of ["/feed", "https://example.org/b", "//api.example.org/items.json"]) {
                parts.push(await (await fetch(a)).text());
              }
              return { records: [{ title: parts.join(""), link: "https://www.example.org/", date: "", rawText: "" }] };
            }
            """.trimIndent(),
            CollectorInput(source, emptyList(), null),
        )
        assertNull(run.failure, run.failure?.reason)
        assertEquals("abc", run.wellFormed.single().title)
    }

    @Test
    fun aRedirectOutsideTheDomainIsRefused() = runTest {
        val transport = FetchTransport { FetchResponse(200, "x", finalUrl = "https://elsewhere.example.com/") }
        val run = engine(transport = transport).run(
            "async function collect() { await fetch('/start'); return { records: [] }; }",
            CollectorInput(source, emptyList(), null),
        )
        assertEquals(FailureKind.FORBIDDEN_FETCH, run.failure?.kind)
        assertTrue("redirected" in run.failure!!.reason)
    }

    @Test
    fun aScriptThatNeverEndsIsStoppedWithinTheTimeLimit() = runTest(timeout = 60.seconds) {
        val limits = CollectorLimits(timeMillis = 1_000)
        for (script in listOf(
            "async function collect() { while (true) {} }",
            "while (true) {}\nfunction collect() {}",
            "async function collect() { for (;;) { await Promise.resolve(); } }",
        )) {
            val started = TimeSource.Monotonic.markNow()
            val run = run(script, limits)
            val elapsed = started.elapsedNow()
            assertEquals(FailureKind.TIMEOUT, run.failure?.kind, script)
            assertEquals(CollectionState.FAILED, run.state)
            assertTrue("1000 ms" in run.failure!!.reason)
            assertTrue(elapsed < 6.seconds, "stopped after $elapsed")
        }
        val hanging = engine(limits, FetchTransport { awaitCancellation() })
            .run("async function collect() { await fetch('/slow'); return { records: [] }; }", CollectorInput(source, keywords))
        assertEquals(FailureKind.TIMEOUT, hanging.failure?.kind)
    }

    @Test
    fun aPromiseThatNeverSettlesIsReported() = runTest {
        val run = run("async function collect() { await new Promise(() => {}); }")
        assertEquals(FailureKind.SCRIPT_ERROR, run.failure?.kind)
        assertTrue("never finished" in run.failure!!.reason)
    }

    @Test
    fun aResultThatIsTooLargeIsRefused() = runTest {
        val limits = CollectorLimits(timeMillis = 5_000, maxOutputChars = 10_000, maxRecords = 5)
        val big = run(returning("[{ title: 'a', link: 'https://www.example.org/', date: '', rawText: 'x'.repeat(20000) }]"), limits)
        assertEquals(FailureKind.OUTPUT_TOO_LARGE, big.failure?.kind)
        assertTrue("10000" in big.failure!!.reason)

        val many = run(
            returning("Array.from({ length: 6 }, (_, i) => ({ title: 't' + i, link: 'https://www.example.org/' + i, date: '', rawText: '' }))"),
            limits,
        )
        assertEquals(FailureKind.OUTPUT_TOO_LARGE, many.failure?.kind)
        assertTrue("6 records" in many.failure!!.reason)

        val memory = run(
            "async function collect() { const a = []; for (;;) a.push('x'.repeat(1000) + a.length); }",
            CollectorLimits(timeMillis = 10_000, memoryBytes = 8L * 1024 * 1024),
        )
        assertEquals(FailureKind.MEMORY, memory.failure?.kind)
    }

    @Test
    fun theScriptCannotReadTheClockOrRandomNumbers() = runTest {
        for ((call, word) in listOf(
            "Date.now()" to "clock",
            "new Date()" to "clock",
            "Date()" to "clock",
            "Object.getPrototypeOf(new Date(0)).constructor.now()" to "clock",
            "Math.random()" to "random",
        )) {
            val run = run(returning("[{ title: String($call), link: 'https://www.example.org/', date: '', rawText: '' }]"))
            assertEquals(FailureKind.SCRIPT_ERROR, run.failure?.kind, call)
            assertTrue(word in run.failure!!.reason, "$call: ${run.failure.reason}")
        }
        // Dates found in a page can still be read, and give the same result every time.
        val script = returning(
            "[{ title: 't', link: 'https://www.example.org/', date: new Date('Thu, 12 Nov 2026 09:30:00 GMT').toISOString(), rawText: '' }]",
        )
        val first = run(script)
        assertEquals("2026-11-12T09:30:00.000Z", first.wellFormed.single().date)
        assertEquals(first, run(script))
    }

    @Test
    fun theScriptSeesNothingOfTheHostButFetch() = runTest {
        val run = run(
            """
            async function collect() {
              const names = Object.getOwnPropertyNames(globalThis).sort();
              const probes = ["require", "process", "std", "os", "scriptArgs", "print", "console", "setTimeout",
                "XMLHttpRequest", "WebAssembly", "WeakRef", "FinalizationRegistry", "performance",
                "__dobraFetch", "__dobraDone", "__dobraFail"].filter((n) => typeof globalThis[n] !== "undefined");
              let imported = "no";
              try { await import("os"); imported = "yes"; } catch (e) { imported = "refused"; }
              return { records: [{ title: imported, link: "https://www.example.org/", date: "",
                rawText: JSON.stringify({ names, probes }) }] };
            }
            """.trimIndent(),
        )
        assertNull(run.failure, run.failure?.reason)
        val record = run.wellFormed.single()
        assertEquals("refused", record.title, "no module can be loaded (no files)")
        val seen = Json.parseToJsonElement(record.rawText).jsonObject
        assertEquals(emptyList(), seen["probes"]!!.jsonArray.map { it.jsonPrimitive.content })
        val names = seen["names"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val host = names - STANDARD_GLOBALS
        assertEquals(setOf("fetch", "collect", "__dobraRun"), host, "the only host additions")
    }

    @Test
    fun aRecordWithoutALinkOrATitleIsRefusedBeforeAnythingUsesIt() = runTest {
        val run = run(
            returning(
                """[
                  { title: 'Falha de segurança', link: 'https://www.example.org/ok', date: '', rawText: '' },
                  { title: 'Sem link sobre segurança', date: '', rawText: '' },
                  { title: '   ', link: 'https://www.example.org/no-title', date: '', rawText: 'segurança' },
                  { title: 'Link estranho sobre segurança', link: 'javascript:alert(1)', date: '', rawText: '' },
                  { title: 'Sem data sobre segurança', link: 'https://www.example.org/no-date', rawText: '' },
                  { title: 'Sinal errado sobre segurança', link: 'https://www.example.org/s', date: '', rawText: '', signal: 'muito' },
                  'segurança'
                ]""",
            ),
        )
        assertEquals(CollectionState.PARTIAL, run.state)
        assertEquals(7, run.returned)
        assertEquals(listOf("https://www.example.org/ok"), run.wellFormed.map { it.link })
        assertEquals(listOf("https://www.example.org/ok"), run.kept.map { it.link })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), run.refused.map { it.index })
        assertTrue("link" in run.refused[0].reason)
        assertTrue("title" in run.refused[1].reason)
        assertTrue("http" in run.refused[2].reason)
        assertTrue("date" in run.refused[3].reason)
        assertTrue("signal" in run.refused[4].reason)
    }

    @Test
    fun scriptErrorsAndBadOutputAreReportedWithTheReason() = runTest {
        val notErasable = run("enum Kind { A }\nasync function collect() { return { records: [] }; }")
        assertEquals(FailureKind.SYNTAX, notErasable.failure?.kind)
        assertTrue("line 1" in notErasable.failure!!.reason)

        val broken = run("async function collect( { return 1 }")
        assertEquals(FailureKind.SYNTAX, broken.failure?.kind)

        val noCollect = run("const x = 1;")
        assertEquals(FailureKind.SCRIPT_ERROR, noCollect.failure?.kind)
        assertTrue("collect" in noCollect.failure!!.reason)

        val throws = run("async function collect() { throw new RangeError('page layout changed'); }")
        assertEquals(FailureKind.SCRIPT_ERROR, throws.failure?.kind)
        assertTrue("RangeError: page layout changed" in throws.failure!!.reason)

        val notAnObject = run("function collect() { return [1, 2]; }")
        assertEquals(FailureKind.BAD_OUTPUT, notAnObject.failure?.kind)

        val badSource = engine().run(returning("[]"), CollectorInput("ftp://example.org/", keywords))
        assertEquals(FailureKind.BAD_INPUT, badSource.failure?.kind)
    }

    @Test
    fun theCursorIsHandedInAndBack() = runTest {
        val script = "function collect(input) { return { records: [], cursor: 'after:' + input.cursor }; }"
        val run = engine().run(script, CollectorInput(source, keywords, "42"))
        assertEquals("after:42", run.cursor)
        val unchanged = engine().run(returning("[]"), CollectorInput(source, keywords, "42"))
        assertEquals(null, unchanged.cursor, "an explicit null cursor resets it")
        val kept = engine().run("function collect() { return { records: [] }; }", CollectorInput(source, keywords, "42"))
        assertNotNull(kept.cursor)
        assertEquals("42", kept.cursor, "no cursor in the result keeps the previous one")
    }

    private companion object {
        /** The ECMAScript globals of QuickJS (without the two removed for determinism). */
        val STANDARD_GLOBALS = setOf(
            "Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError",
            "InternalError", "AggregateError", "Array", "Object", "Function", "Iterator", "parseInt", "parseFloat",
            "isNaN", "isFinite", "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent", "escape",
            "unescape", "Infinity", "NaN", "undefined", "eval", "Number", "Boolean", "String", "Math", "Reflect",
            "Symbol", "globalThis", "BigInt", "Date", "RegExp", "JSON", "Proxy", "Map", "Set", "WeakMap", "WeakSet",
            "ArrayBuffer", "SharedArrayBuffer", "Uint8ClampedArray", "Int8Array", "Uint8Array", "Int16Array",
            "Uint16Array", "Int32Array", "Uint32Array", "BigInt64Array", "BigUint64Array", "Float16Array",
            "Float32Array", "Float64Array", "DataView", "Atomics", "Promise",
        )
    }
}
