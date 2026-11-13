package dobra.make

/** Reads the primary page of a record for the writing step. Answers the page's plain text, or null when unreadable. */
fun interface PageReader {
    suspend fun read(url: String): String?
}

/** Turns an HTML page into the plain text the model reads: no scripts, styles, tags or repeated spaces. */
object PageText {
    private val DROP = Regex("(?is)<(script|style|noscript|svg|template)\\b.*?</\\1\\s*>|<!--.*?-->")
    private val BREAK = Regex("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/blockquote|/pre)\\b[^>]*>")
    private val TAG = Regex("<[^>]*>")
    private val NUMERIC = Regex("&#(x?)([0-9a-fA-F]+);")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val LINES = Regex("\\s*\\n\\s*(\\n\\s*)*")

    fun fromHtml(html: String, max: Int = 20_000): String {
        var t = DROP.replace(html, " ")
        t = BREAK.replace(t, "\n")
        t = TAG.replace(t, " ")
        t = NUMERIC.replace(t) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code == null || code !in 1..0xFFFF) " " else code.toChar().toString()
        }
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
        t = SPACES.replace(t, " ")
        t = LINES.replace(t, "\n")
        return t.trim().take(max)
    }
}
