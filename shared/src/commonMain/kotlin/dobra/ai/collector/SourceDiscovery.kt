package dobra.ai.collector

import dobra.collect.engine.FetchTransport
import dobra.collect.engine.SourceDomain
import dobra.collect.engine.WebAddress
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Finds evidence the site actually supplies; never guesses endpoints or executes the site's JavaScript. */
internal object SourceDiscovery {
    const val EVIDENCE_CHARS = 20_000
    private const val SAMPLE_CHARS = 8_000
    private const val MAX_FETCHES = 2
    private const val MAX_BODY_CHARS = 2_000_000
    private val links = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val scripts = Regex("""<script\b([^>]*)>([\s\S]*?)</script\s*>""", RegexOption.IGNORE_CASE)
    private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val feedTypes = setOf("application/rss+xml", "application/atom+xml", "application/feed+json", "application/json")

    private fun attrs(tag: String): Map<String, String> = attributes.findAll(tag).associate { match ->
        match.groupValues[1].lowercase() to (match.groups[2]?.value ?: match.groups[3]?.value ?: match.groupValues[4])
    }

    /** Only explicitly announced feeds/JSON on the domain already allowed by the collector sandbox. */
    fun announced(fixture: Fixture): List<String> {
        val base = WebAddress.parse(fixture.url) ?: return emptyList()
        val domain = SourceDomain(base)
        return links.findAll(fixture.main?.body.orEmpty()).mapNotNull { match ->
            val a = attrs(match.value)
            if ("alternate" !in a["rel"].orEmpty().lowercase().split(Regex("\\s+")) ||
                a["type"].orEmpty().substringBefore(';').trim().lowercase() !in feedTypes) return@mapNotNull null
            val href = a["href"]?.replace("&amp;", "&") ?: return@mapNotNull null
            val address = base.resolve(href.substringBefore('#')) ?: return@mapNotNull null
            address.toString().takeIf { domain.allows(address) && it != fixture.url }
        }.distinct().take(MAX_FETCHES).toList()
    }

    /** At most two extra GETs, once each. Refusals stop discovery; the main page remains available as a fallback. */
    suspend fun enrich(fixture: Fixture, live: FetchTransport?): Fixture {
        if (live == null) return fixture
        var copy = fixture
        for (url in announced(fixture)) {
            if (copy.page(url) != null) continue
            val response = try {
                live.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                copy = copy.with(SavedPage(url, 0, "The transport could not read this announced endpoint."))
                continue
            }
            // Do not describe a redirected response as evidence for an endpoint we did not actually observe.
            if ((response.finalUrl ?: url) != url || response.body.length > MAX_BODY_CHARS) continue
            copy = copy.with(SavedPage(url, response.status, response.body, response.contentType))
            if (response.status == 403 || response.status == 429) break
        }
        return copy
    }

    /** Bounded samples before the raw HTML, including JSON located beyond its first 60k characters. */
    fun evidence(fixture: Fixture): String = buildString {
        fun sample(label: String, body: String) {
            val room = EVIDENCE_CHARS - length - label.length - 100
            if (room <= 0) return
            append(label).append('\n')
            val shown = body.take(minOf(SAMPLE_CHARS, room))
            append(shown).append('\n')
            if (shown.length < body.length) append("(sample truncated; read the saved response for the complete data)\n")
        }
        val announced = announced(fixture)
        if (announced.isNotEmpty()) append("Announced feed/JSON addresses (not inferred):\n").append(announced.joinToString("\n")).append("\n")
        fixture.pages.filter { it.url != fixture.url }.take(4).forEach { page ->
            sample("## Observed response <${page.url}> (HTTP ${page.status}, ${page.contentType ?: "unknown type"})", page.body)
        }
        var count = 0
        for (script in scripts.findAll(fixture.main?.body.orEmpty())) {
            val a = attrs(script.groupValues[1])
            val type = a["type"].orEmpty().substringBefore(';').trim().lowercase()
            if (type != "application/json" && type != "application/ld+json") continue
            val body = script.groupValues[2].trim()
            val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
            if (json !is JsonObject && json !is JsonArray) continue
            sample("## Embedded JSON (id=${a["id"] ?: "none"}, type=$type)", body)
            if (++count == 3 || length >= EVIDENCE_CHARS - 100) break
        }
    }.take(EVIDENCE_CHARS)
}
