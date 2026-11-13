package dobra.collect.engine

/**
 * The magazine's keywords decide what a collector keeps (PLAN-collectors, "The collector contract"): a record is kept
 * when at least one keyword appears in its title or its text, word for word, ignoring case and accents. A keyword of
 * several words matches those words in a row. An empty list disables the engine's keyword prefilter.
 *
 * Folding is done here in common code with a fixed table (not the platform's Unicode normaliser), so the result is
 * the same on every platform.
 */
object KeywordMatcher {
    /** The keywords (as given) found in [title] or [text], in the order of [keywords]. */
    fun matching(keywords: List<String>, title: String, text: String): List<String> {
        val titleWords = words(title)
        val textWords = words(text)
        return keywords.filter { keyword ->
            val wanted = words(keyword)
            wanted.isNotEmpty() && (containsRun(titleWords, wanted) || containsRun(textWords, wanted))
        }
    }

    fun matches(keyword: String, title: String, text: String): Boolean =
        matching(listOf(keyword), title, text).isNotEmpty()

    /** The words of [s]: lower case, without accents, split at everything that is not a letter or a digit. */
    fun words(s: String): List<String> {
        val folded = fold(s)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in folded) {
            if (c.isLetterOrDigit()) {
                current.append(c)
            } else if (current.isNotEmpty()) {
                out.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** Lower case without accents: "Segurança" becomes "seguranca". */
    fun fold(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s.lowercase()) {
            if (c in '̀'..'ͯ') continue // combining accents (text in decomposed form)
            val plain = FOLD[c]
            if (plain != null) out.append(plain) else out.append(c)
        }
        return out.toString()
    }

    private fun containsRun(words: List<String>, run: List<String>): Boolean {
        if (run.size > words.size) return false
        for (start in 0..words.size - run.size) {
            var all = true
            for (k in run.indices) {
                if (words[start + k] != run[k]) {
                    all = false
                    break
                }
            }
            if (all) return true
        }
        return false
    }

    private val FOLD: Map<Char, String> = buildMap {
        fun map(chars: String, plain: String) = chars.forEach { put(it, plain) }
        map("àáâãäåāăąǎ", "a")
        map("çćĉċč", "c")
        map("ďđ", "d")
        map("èéêëēĕėęě", "e")
        map("ĝğġģ", "g")
        map("ĥħ", "h")
        map("ìíîïĩīĭįıǐ", "i")
        map("ĵ", "j")
        map("ķ", "k")
        map("ĺļľŀł", "l")
        map("ñńņňŉ", "n")
        map("òóôõöøōŏőǒ", "o")
        map("ŕŗř", "r")
        map("śŝşšș", "s")
        map("ţťŧț", "t")
        map("ùúûüũūŭůűųǔ", "u")
        map("ŵ", "w")
        map("ýÿŷ", "y")
        map("źżž", "z")
        put('ß', "ss")
        put('æ', "ae")
        put('œ', "oe")
        put('þ', "th")
        put('ð', "d")
    }
}
