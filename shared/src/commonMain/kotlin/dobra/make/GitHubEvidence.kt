package dobra.make

import dobra.collect.engine.FetchTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Bounded, deterministic repository investigation. No scripts, model calls, or external documentation hosts. */
class GitHubEvidence(
    private val transport: FetchTransport,
    private val nowMillis: () -> Long,
) {
    private data class Cached(val until: Long, val pages: List<PageEvidence>)
    private data class File(val path: String)

    private val lock = Mutex()
    private val repositories = linkedMapOf<String, Cached>()
    private val files = linkedMapOf<String, String>()
    private val apiCalls = ArrayList<Long>()
    private var blockedUntil = 0L

    suspend fun read(url: String): List<PageEvidence> {
        val repo = repository(url) ?: return emptyList()
        return lock.withLock {
            val now = nowMillis()
            repositories[repo]?.takeIf { it.until > now }?.let { return@withLock it.pages }
            val pages = ArrayList<PageEvidence>()
            // Return successful documents even if a later request runs out of time.
            withTimeoutOrNull(45_000) { collect(repo, pages) }
            repositories.remove(repo)
            repositories[repo] = Cached(nowMillis() + if (pages.isEmpty()) 60_000 else 900_000, pages.toList())
            while (repositories.size > 64) repositories.remove(repositories.keys.first())
            pages.toList()
        }
    }

    private suspend fun collect(repo: String, pages: MutableList<PageEvidence>) {
        // Omitting sha asks GitHub for the default branch, whatever its name. Pin all files to that commit.
        val head = api("https://api.github.com/repos/$repo/commits?per_page=1") ?: return
        val commit = head.string("sha")?.takeIf { SHA.matches(it) } ?: return
        val tree = api("https://api.github.com/repos/$repo/git/trees/$commit?recursive=1") ?: return
        val entries = (tree["tree"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val path = o.string("path") ?: return@mapNotNull null
            val size = (o["size"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            if (o.string("type") != "blob" || !safePath(path) || size !in 1..MAX_FILE_BYTES) null else File(path)
        }.filter { priority(it.path) > 0 }.sortedWith(compareByDescending<File> { priority(it.path) }.thenBy { it.path })
        // A truncated tree is still useful, but never presented as a complete inventory.
        val incomplete = (tree["truncated"] as? JsonPrimitive)?.booleanOrNull == true
        val knownPaths = entries.map { it.path }.toSet()
        val pending = entries.toMutableList()
        val visited = HashSet<String>()
        var attempts = 0
        var chars = 0
        while (pending.isNotEmpty() && attempts < MAX_FILES && chars < MAX_TEXT_CHARS) {
            val file = pending.removeAt(0)
            if (!visited.add(file.path)) continue
            attempts++
            val raw = "https://raw.githubusercontent.com/$repo/$commit/${pathUrl(file.path)}"
            val text = files[raw] ?: fetch(raw)?.takeIf { it.isNotBlank() && it.length <= MAX_FILE_BYTES }?.also {
                files[raw] = it
                while (files.size > 128) files.remove(files.keys.first())
            } ?: continue
            val cite = "https://github.com/$repo/blob/$commit/${pathUrl(file.path)}"
            val excerpt = excerpt(file.path, text).take(minOf(4_000, MAX_TEXT_CHARS - chars))
            pages += PageEvidence(cite, "File: ${file.path}\n" +
                (if (incomplete) "Repository inventory was truncated; only listed files were considered.\n" else "") + excerpt)
            chars += excerpt.length
            // Follow only existing, eligible local files referenced by documentation, once each.
            val linked = MARKDOWN_LINK.findAll(text).mapNotNull { match ->
                resolve(file.path, match.groupValues[1], repo, knownPaths)
            }.toSet()
            val promoted = pending.filter { it.path in linked && priority(it.path) >= 65 }
            pending.removeAll(promoted.toSet())
            pending.addAll(0, promoted)
        }
    }

    private suspend fun api(url: String): JsonObject? {
        val now = nowMillis()
        apiCalls.removeAll { it <= now - HOUR }
        if (now < blockedUntil || apiCalls.size >= 48) return null
        apiCalls += now
        val body = fetch(url) ?: return null
        return try {
            when (val value = Json.parseToJsonElement(body)) {
                is JsonObject -> value
                is JsonArray -> value.firstOrNull() as? JsonObject
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private suspend fun fetch(url: String): String? {
        return try {
            if (nowMillis() < blockedUntil) return null
            val response = transport.get(url)
            if (response.status == 403 || response.status == 429) {
                blockedUntil = nowMillis() + HOUR
                null
            } else if (response.status !in 200..299 ||
                (response.finalUrl != null && response.finalUrl != url) ||
                response.contentType.orEmpty().contains("text/html", ignoreCase = true)) null
            else response.body
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_FILES = 8
        const val MAX_FILE_BYTES = 64_000L
        const val MAX_TEXT_CHARS = 32_000
        private const val HOUR = 3_600_000L
        private val SHA = Regex("[a-fA-F0-9]{40,64}")
        private val REPO = Regex("https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:[/?#].*)?", RegexOption.IGNORE_CASE)
        private val MARKDOWN_LINK = Regex("\\]\\(<?([^\\s)>]+)>?(?:\\s+[^)]*)?\\)")
        private val EXCLUDED = setOf("node_modules", "vendor", "dist", "build", "target", ".git", "generated", "translations", "locales")
        private val MANIFESTS = setOf("package.json", "pyproject.toml", "cargo.toml", "go.mod", "gemfile", "composer.json")

        /** Recognizes repository links, including a repository discovered by another source such as Hacker News. */
        fun repository(url: String): String? {
            val m = REPO.matchEntire(url) ?: return null
            val owner = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")
            if (owner.lowercase() in setOf("topics", "trending", "collections", "search", "marketplace", "orgs", "users", "settings", "features", "sponsors") ||
                owner in setOf(".", "..") || repo in setOf("", ".", "..")) return null
            return "$owner/$repo"
        }

        private fun priority(path: String): Int {
            val parts = path.lowercase().split('/')
            val name = parts.last()
            if (parts.any { it in EXCLUDED } || name.contains(".min.") || name.endsWith(".lock")) return 0
            if (name.matches(Regex("readme[._-][a-z]{2}(?:[._-][a-z]{2})?\\.(md|rst|txt)"))) return 0
            if (parts.size == 1 && name in setOf("readme.md", "readme.rst", "readme.txt", "readme")) return 100
            if (name == "skill.md") return 95
            if (name in MANIFESTS || name.endsWith(".csproj")) return if (parts.size == 1) 90 else 55
            val text = name.endsWith(".md") || name.endsWith(".mdx") || name.endsWith(".rst") || name.endsWith(".txt")
            if (text && listOf("getting-started", "getting_started", "quickstart", "installation", "usage", "tutorial", "configuration").any { it in name }) return 85
            if (text && parts.any { it in setOf("docs", "doc", "guides", "guide") }) return 75
            if (parts.any { it in setOf("examples", "samples") } &&
                (text || name.substringAfterLast('.') in setOf("json", "yaml", "yml", "toml", "py", "rs", "ts", "js", "cs", "sh"))) return 65
            if (name in setOf("license", "license.md", "license.txt", "copying")) return 45
            if (name in setOf("changelog.md", "changes.md", "history.md")) return 40
            return 0
        }

        private fun safePath(path: String) = path.isNotEmpty() && path.split('/').none { it in setOf("", ".", "..") } &&
            path.none { it == '\\' || it.code < 32 || it == '%' || it == '?' || it == '#' }

        private fun resolve(from: String, destination: String, repo: String, knownPaths: Set<String>): String? {
            var path = destination.substringBefore('#').substringBefore('?')
            val prefix = "https://github.com/$repo/blob/"
            // Use the known path, not a guessed branch name (branch names themselves may contain slashes).
            if (path.startsWith(prefix)) {
                val tail = path.removePrefix(prefix)
                return knownPaths.filter { tail.endsWith("/$it") }.maxByOrNull { it.length }
            }
            if (':' in path || path.startsWith("//")) return null
            if (path.isBlank()) return null
            val base = if (path.startsWith('/')) "" else from.substringBeforeLast('/', "")
            val result = ArrayList<String>()
            for (part in "$base/$path".split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (result.isEmpty()) return null else result.removeAt(result.lastIndex)
                else -> result += part
            }
            return result.joinToString("/").takeIf(::safePath)
        }

        private fun excerpt(path: String, text: String): String {
            if (path.substringAfterLast('/').equals("package.json", ignoreCase = true)) {
                val o = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
                if (o != null) return JsonObject(o.filterKeys {
                    it in setOf("name", "version", "description", "license", "bin", "engines", "exports", "scripts", "peerDependencies")
                }).toString()
            }
            // Keep headings and opening usage examples; remove badge-only lines that waste the bounded excerpt.
            return text.lineSequence().filterNot { line ->
                line.contains("![") && (line.contains("shields.io") || line.contains("badge"))
            }.joinToString("\n").trim()
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content
        private fun pathUrl(path: String) = path.split('/').joinToString("/", transform = ::encode)
        private fun encode(text: String): String = buildString {
            text.encodeToByteArray().forEach { byte ->
                val b = byte.toInt() and 255
                if (b in 65..90 || b in 97..122 || b in 48..57 || b.toChar() in "-._~") append(b.toChar())
                else append('%').append("0123456789ABCDEF"[b shr 4]).append("0123456789ABCDEF"[b and 15])
            }
        }
    }
}
