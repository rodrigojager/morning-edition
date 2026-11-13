package dobra.ai.collector

import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import dobra.collect.engine.SavedPages
import dobra.collect.engine.WebAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** One page of a saved copy, as the site answered it. */
@Serializable
data class SavedPage(val url: String, val status: Int, val body: String, val contentType: String? = null)

/**
 * The saved copy of a source (the "fixture" of PLAN-collectors): the page at the source's address, plus any other page
 * of the same site a collector read while it was being accepted. A collector is accepted only against this copy.
 */
@Serializable
data class Fixture(val url: String, val savedAt: String, val pages: List<SavedPage>) {
    /** The page at the source's own address. */
    val main: SavedPage? get() = pages.firstOrNull { it.url == url }

    fun page(address: String): SavedPage? = pages.firstOrNull { it.url == address }

    /** The copy with [page] added (or replacing the page of the same address). */
    fun with(page: SavedPage): Fixture = copy(pages = pages.filter { it.url != page.url } + page)

    /** Serves only the saved pages; any other address answers 404. */
    fun transport(): FetchTransport =
        SavedPages(pages.associate { it.url to FetchResponse(it.status, it.body, it.contentType, it.url) })
}

/** Where the saved copies are kept, by key (the source's [dobra.model.Source.fixture] holds the key). */
interface FixtureStore {
    suspend fun get(key: String): Fixture?
    suspend fun put(key: String, fixture: Fixture)
    suspend fun delete(key: String)
}

class MemoryFixtureStore : FixtureStore {
    private val fixtures = HashMap<String, Fixture>()
    override suspend fun get(key: String) = fixtures[key]
    override suspend fun put(key: String, fixture: Fixture) {
        fixtures[key] = fixture
    }
    override suspend fun delete(key: String) {
        fixtures.remove(key)
    }
    val keys: Set<String> get() = fixtures.keys.toSet()
}

/** What fetching a source's page once gave. */
sealed interface PageCopy {
    data class Saved(val fixture: Fixture) : PageCopy

    /** The site refuses automated reading (HTTP 403 or 429). The app does not try again with tricks. */
    data class Refused(val status: Int, val reason: String) : PageCopy

    /** The page could not be read (not a web address, no network, another HTTP error). */
    data class Failed(val reason: String) : PageCopy
}

object PageCopier {
    /** Fetches [url] once through [live] and keeps what it answered as a new saved copy. */
    suspend fun copy(url: String, live: FetchTransport, savedAt: String): PageCopy {
        if (WebAddress.parse(url) == null) return PageCopy.Failed("the address is not an http or https address: $url")
        val response = try {
            live.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PageCopy.Failed("the page could not be read: ${e.message ?: e::class.simpleName}")
        }
        if (response.status == 403 || response.status == 429) {
            return PageCopy.Refused(response.status, "the site refused automated reading (HTTP ${response.status})")
        }
        if (response.status !in 200..299) return PageCopy.Failed("the page answered HTTP ${response.status}")
        if (response.body.isBlank()) return PageCopy.Failed("the page answered with nothing in it")
        return PageCopy.Saved(Fixture(url, savedAt, listOf(SavedPage(url, response.status, response.body, response.contentType))))
    }
}

/**
 * Serves the saved pages, and fetches an address the copy does not have yet through [live], adding what it answered
 * to the copy. Used only for one warm-up run before the acceptance runs, so a collector that reads the site's feed
 * (or a second page) is accepted against a copy that has it. The engine has already limited the address to the site.
 */
internal class FillingPages(start: Fixture, private val live: FetchTransport) : FetchTransport {
    private val mutex = Mutex()
    var fixture: Fixture = start
        private set

    override suspend fun get(url: String): FetchResponse {
        mutex.withLock { fixture.page(url) }?.let { return FetchResponse(it.status, it.body, it.contentType, it.url) }
        val response = live.get(url)
        val final = response.finalUrl ?: url
        if (final == url) mutex.withLock { fixture = fixture.with(SavedPage(url, response.status, response.body, response.contentType)) }
        return response
    }
}
