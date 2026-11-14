package dobra.make

import dobra.collect.engine.FetchResponse
import dobra.collect.engine.FetchTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubEvidenceTest {
    private val commit = "a".repeat(40)
    private val treeSha = "b".repeat(40)
    private val repo = "https://github.com/acme/tool"
    private val head = "https://api.github.com/repos/acme/tool/commits?per_page=1"
    private val treeUrl = "https://api.github.com/repos/acme/tool/git/trees/$commit?recursive=1"
    private fun raw(path: String) = "https://raw.githubusercontent.com/acme/tool/$commit/$path"

    private fun tree(vararg paths: String, truncated: Boolean = false) = buildJsonObject {
        put("sha", treeSha)
        put("truncated", truncated)
        put("tree", buildJsonArray {
            paths.forEach { path -> add(buildJsonObject {
                put("path", path); put("type", "blob"); put("size", if (path == "docs/huge.md") 100_000 else 100)
            }) }
        })
    }.toString()

    private class Transport(val responses: Map<String, FetchResponse>) : FetchTransport {
        val calls = ArrayList<String>()
        override suspend fun get(url: String): FetchResponse {
            calls += url
            return responses[url] ?: FetchResponse(404, "")
        }
    }

    private fun transport(tree: String, vararg files: Pair<String, FetchResponse>) = Transport(mapOf(
        head to FetchResponse(200, """[{"sha":"$commit"}]""", "application/json"),
        treeUrl to FetchResponse(200, tree, "application/json"),
    ) + files)

    @Test
    fun collectsDocsExamplesSkillsAndManifestPinnedToCommitRatherThanTreeSha() = runTest {
        val paths = arrayOf("README.md", "docs/usage.md", "examples/demo.py", "SKILL.md", "package.json", "LICENSE")
        val t = transport(tree(*paths), *paths.map { raw(it) to FetchResponse(200,
            if (it == "package.json") """{"name":"tool","bin":{"tool":"cli.js"},"dependencies":{"noise":"1"}}"""
            else "Evidence from $it", "text/plain") }.toTypedArray())
        val result = GitHubEvidence(t) { 0 }.read(repo)
        assertEquals(6, result.size)
        assertTrue(result.all { "/blob/$commit/" in it.url })
        assertFalse(result.any { treeSha in it.url })
        val manifest = result.single { it.url.endsWith("package.json") }.text
        assertTrue("cli.js" in manifest)
        assertFalse("noise" in manifest)
        assertEquals(8, t.calls.size)
    }

    @Test
    fun followsExistingLocalDocLinksBeforeOtherFilesAndNeverFetchesExternalLinks() = runTest {
        val paths = arrayOf("README.md", "docs/a.md", "docs/z.md", "package.json", "LICENSE")
        val t = transport(tree(*paths),
            raw("README.md") to FetchResponse(200, "[license](LICENSE) [guide](https://github.com/acme/tool/blob/feature/docs/docs/z.md) [bad](https://evil.example/x) [escape](../secret.md) [missing](docs/missing.md)"),
            raw("docs/z.md") to FetchResponse(200, "[cycle](../README.md)\nUsage: tool run"),
            raw("docs/a.md") to FetchResponse(200, "Another guide"))
        val result = GitHubEvidence(t) { 0 }.read(repo)
        assertTrue(result[1].url.endsWith("docs/z.md"))
        assertEquals(1, t.calls.count { it == raw("README.md") })
        assertTrue(t.calls.all { it.startsWith("https://api.github.com/") || it.startsWith("https://raw.githubusercontent.com/acme/tool/$commit/") })
        assertFalse(t.calls.any { "missing.md" in it || "secret.md" in it })
    }

    @Test
    fun skipsGeneratedBinaryTranslatedOversizedAndUnsafeFilesAndCapsFetchesAndText() = runTest {
        val ignored = arrayOf("vendor/README.md", "docs/huge.md", "README.pt-BR.md", "docs/image.png", "docs/../secret.md", "docs/%2e%2e.md", "node_modules/a/package.json")
        val docs = (1..20).map { "docs/guide$it.md" }
        val t = transport(tree(*(ignored + docs)), *docs.map { raw(it) to FetchResponse(200, "x".repeat(8_000)) }.toTypedArray())
        val result = GitHubEvidence(t) { 0 }.read(repo)
        assertEquals(8, result.size)
        assertEquals(10, t.calls.size)
        assertTrue(result.sumOf { it.text.substringAfter('\n').length } <= GitHubEvidence.MAX_TEXT_CHARS)
        assertTrue(t.calls.none { call -> ignored.any { call.endsWith(it) } })
    }

    @Test
    fun cachesRepositoryAndReusesUnchangedCommitFilesAfterMetadataExpiry() = runTest {
        var now = 0L
        val t = transport(tree("README.md"), raw("README.md") to FetchResponse(200, "Install tool"))
        val reader = GitHubEvidence(t) { now }
        val first = reader.read(repo)
        assertEquals(first, reader.read("$repo/"))
        assertEquals(3, t.calls.size)
        now = 901_000
        assertEquals(first, reader.read(repo))
        assertEquals(5, t.calls.size)
        assertEquals(1, t.calls.count { it == raw("README.md") })
    }

    @Test
    fun keepsSuccessfulFilesAfter404AndRejectsHtmlOrRedirectedBodies() = runTest {
        val t = transport(tree("README.md", "docs/a.md", "docs/b.md", "docs/c.md"),
            raw("README.md") to FetchResponse(200, "Valid README"),
            raw("docs/b.md") to FetchResponse(200, "Login page", "text/html"),
            raw("docs/c.md") to FetchResponse(200, "Redirected", finalUrl = "https://other.example"))
        assertEquals(1, GitHubEvidence(t) { 0 }.read(repo).size)
    }

    @Test
    fun refusesApiRateLimitWithoutRetryAndRecoversAfterCooldown() = runTest {
        var now = 0L
        val t = Transport(mapOf(head to FetchResponse(429, "limited")))
        val reader = GitHubEvidence(t) { now }
        assertTrue(reader.read(repo).isEmpty())
        now = 61_000
        assertTrue(reader.read(repo).isEmpty())
        assertEquals(1, t.calls.size)
        now = 3_700_000
        reader.read(repo)
        assertEquals(2, t.calls.size)
    }

    @Test
    fun apiBudgetNeverExceeds48CallsPerHour() = runTest {
        val calls = ArrayList<String>()
        val reader = GitHubEvidence(FetchTransport { url ->
            calls += url
            FetchResponse(200, if ("/commits?" in url) """[{"sha":"$commit"}]""" else tree())
        }) { 0 }
        repeat(30) { reader.read("https://github.com/acme/tool$it") }
        assertEquals(48, calls.size)
    }

    @Test
    fun timeoutKeepsEarlierEvidenceAndCallerCancellationPropagates() = runTest {
        val reader = GitHubEvidence(FetchTransport { url -> when (url) {
            head -> FetchResponse(200, """[{"sha":"$commit"}]""")
            treeUrl -> FetchResponse(200, tree("README.md", "docs/usage.md"))
            raw("README.md") -> FetchResponse(200, "Useful evidence")
            else -> { delay(100_000); FetchResponse(200, "late") }
        } }) { testScheduler.currentTime }
        assertEquals(1, reader.read(repo).size)
        val cancelled = GitHubEvidence(FetchTransport { throw CancellationException("cancelled") }) { 0 }
        assertFailsWith<CancellationException> { cancelled.read(repo) }
    }

    @Test
    fun truncatedInventoryIsExplicitAndMalformedOrEmptyRepositoriesAreSafe() = runTest {
        val t = transport(tree("README.md", truncated = true), raw("README.md") to FetchResponse(200, "Tool"))
        assertTrue(GitHubEvidence(t) { 0 }.read(repo).single().text.contains("truncated"))
        for (body in listOf("[]", "not json", "{}")) {
            assertTrue(GitHubEvidence(Transport(mapOf(head to FetchResponse(200, body)))) { 0 }.read(repo).isEmpty())
        }
    }

    @Test
    fun onlyRecognizesRealGitHubRepositoryAddresses() {
        assertEquals("acme/tool", GitHubEvidence.repository("$repo.git"))
        assertEquals("acme/tool", GitHubEvidence.repository("$repo/releases/tag/v1"))
        for (url in listOf("https://github.com/trending", "https://github.com/topics/ai", "https://github.com.evil/acme/tool", "https://github.com/acme/..", "https://github.com@evil/acme/tool")) {
            assertEquals(null, GitHubEvidence.repository(url), url)
        }
    }
}
