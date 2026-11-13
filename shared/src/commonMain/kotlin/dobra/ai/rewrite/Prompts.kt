package dobra.ai.rewrite

import dobra.collect.engine.RawRecord
import dobra.format.ItemFormat
import dobra.model.DobraJson
import dobra.model.Item
import dobra.model.Magazine
import kotlinx.serialization.json.JsonObject

/**
 * What the rewriting job knows about the magazine: its recipe, the reader's feedback and switched-off interests, and
 * the links already published. Nothing about the reader as a person goes in it.
 */
data class Brief(
    val magazine: Magazine,
    val published: List<String> = emptyList(),
    /** Titles of items the reader asked more of, and less of. */
    val liked: List<String> = emptyList(),
    val disliked: List<String> = emptyList(),
    val interestsOff: Set<String> = emptySet(),
) {
    fun render(): String = buildString {
        val m = magazine
        append("# The magazine\n")
        append("Name: ").append(m.name).append('\n')
        if (m.about.isNotBlank()) append("About: ").append(m.about).append('\n')
        append("Subjects (with weight): ")
        append(if (m.topics.isEmpty()) "(none given)" else m.topics.joinToString(", ") { "${it.name} (${it.weight})" })
        append('\n')
        if (m.keywords.isNotEmpty()) append("Keywords the collectors matched: ").append(m.keywords.joinToString(", ")).append('\n')
        append("Language of everything you write: ").append(languageName(m.language)).append('\n')
        append("Tone: ").append(toneName(m.tone)).append('\n')
        append("Instructions from the reader:\n").append(m.prompt.ifBlank { "(none)" }).append('\n')
        if (liked.isNotEmpty()) append("The reader asked for more like: ").append(liked.joinToString("; ")).append('\n')
        if (disliked.isNotEmpty()) append("The reader asked for less like: ").append(disliked.joinToString("; ")).append('\n')
        if (interestsOff.isNotEmpty()) {
            append("Interests the reader switched off (leave them out): ").append(interestsOff.joinToString(", ")).append('\n')
        }
        append("\n# Links already published in this magazine\n")
        if (published.isEmpty()) append("(none yet)\n")
        published.takeLast(MAX_PUBLISHED).forEach { append("- ").append(it).append('\n') }
    }

    companion object {
        const val MAX_PUBLISHED = 300

        fun languageName(code: String): String = when (code) {
            "pt-BR" -> "Brazilian Portuguese (pt-BR)"
            "en" -> "English (en)"
            "es" -> "Spanish (es)"
            else -> code
        }

        fun toneName(tone: String): String = when (tone) {
            "didatico" -> "didatico (plain and didactic: short sentences, every technical term explained where it first appears)"
            "equilibrado" -> "equilibrado (balanced: plain, explaining only the less common terms)"
            "direto" -> "direto (direct: short, no explanation of common terms)"
            "" -> "plain and didactic"
            else -> tone
        }
    }
}

/** One record as the job sees it: [ref] names it in the prompts (r1, r2...). */
data class RecordRef(
    val ref: String,
    val sourceName: String,
    val sourceUrl: String?,
    val record: RawRecord,
    /** Its link is among the links already published: it may only come back as an update. */
    val publishedBefore: Boolean,
) {
    /** The links an item written from this record may cite. */
    val links: List<String> get() = listOfNotNull(record.link, sourceUrl).distinct()
}

/** The texts sent to the rewriting model. Instructions are English; the model writes in the magazine's language. */
object Prompts {
    const val FILTER_TEXT_CHARS = 1_500
    const val WRITE_TEXT_CHARS = 12_000
    const val PAGE_CHARS = 20_000

    val FILTER_SYSTEM = """
        You are the editor of a personal magazine read by one person. You choose, among raw records collected from
        the magazine's sources, the few that deserve an item in this edition. Relevance is judged by the magazine's
        instructions and subjects; popularity is one signal, not the criterion. Leave out clickbait, wrappers with
        nothing of their own, abandoned projects, opportunistic forks and suspicious signals. Three to eight good items
        are better than filling a quota; fewer, even none, is a correct result. A record whose link was already
        published may come back only as an update, and only when its text shows something new.
        You answer with one JSON object and nothing else.
    """.trimIndent()

    val WRITE_SYSTEM = """
        You write one item of a personal magazine as a list of typed blocks, following the magazine's format exactly.
        The verification rule: a fact is only what is in the collected text given to you (the record and, when given,
        its primary page). Blocks "facts", "factsheet" and "evidence" may state only that, and the "sources" block must
        link it. What you know by yourself is never stated as fact. An application to the reader's own context goes
        in a "hypothesis" block. What could not be read or confirmed goes in a "note" block, in plain words. A "quote"
        reproduces the original words and links them; a translation is marked as such. "image" and "video" blocks
        may use only the media addresses listed for the record, and show the origin in "credit". You may add an
        "illustration" you made yourself to help understanding; it never stands for an image of the source.
        Writing: plain and didactic unless the tone says otherwise; the summary says what the thing does and what it
        is for; every technical term is explained where it first appears; not editorial.
        You answer with one JSON object and nothing else.
    """.trimIndent()

    val REFORMAT_SYSTEM = """
        You rewrite one existing item of a personal magazine so that it follows the magazine's current format: only
        the accepted block types, each with its fields. Keep every fact, quote and link the item already has; add no
        fact, link or media of your own. Content of a block type that is no longer accepted is rewritten into accepted
        types, or dropped when no accepted type can carry it. Keep the language and tone of the magazine.
        You answer with one JSON object and nothing else.
    """.trimIndent()

    fun filter(brief: Brief, records: List<RecordRef>, maxItems: Int): String = buildString {
        append(brief.render())
        append("\n# Records collected for this edition\n")
        records.forEach { r ->
            append("\n[").append(r.ref).append("] source: ").append(r.sourceName)
            r.sourceUrl?.let { append(" <").append(it).append('>') }
            append('\n')
            append("title: ").append(r.record.title).append('\n')
            append("link: ").append(r.record.link).append('\n')
            if (r.record.date.isNotBlank()) append("date: ").append(r.record.date).append('\n')
            r.record.signal?.let { append("signal: ").append(it).append('\n') }
            if (r.record.media.isNotEmpty()) append("media: ").append(r.record.media.size).append(" address(es)\n")
            if (r.publishedBefore) append("already published: yes (only as an update that says what changed)\n")
            append("text: ").append(clip(r.record.rawText, FILTER_TEXT_CHARS)).append('\n')
        }
        append("\n# What to answer\n")
        append("A JSON object:\n")
        append("""{"keep": [{"records": ["r1"], "kind": "story", "update": false, "why": "..."}], """)
        append(""""leftOut": [{"record": "r2", "reason": "..."}]}""").append('\n')
        append("- \"keep\": at most ").append(maxItems).append(" entries, best first. \"records\" lists the refs one item is ")
        append("written from (more than one only when they are about the same thing). \"kind\" is \"story\" or \"note\" ")
        append("(a note is a short item). \"update\" is true only for a record already published.\n")
        append("- \"leftOut\": the records that looked relevant but were left out, with the reason in ")
        append(Brief.languageName(brief.magazine.language)).append(". There is no need to list every record.\n")
    }

    /** [pages] holds the text of the primary page by link; a link absent from it could not be read. */
    fun write(
        brief: Brief,
        schema: JsonObject,
        accepted: List<String>,
        records: List<RecordRef>,
        pages: Map<String, String?>,
        kind: String,
        previous: String?,
    ): String = buildString {
        append(brief.render())
        append("\n# The format of this magazine: the JSON Schema of one item\n")
        append(DobraJson.encodeToString(JsonObject.serializer(), schema)).append('\n')
        append("Accepted block types: ").append(accepted.joinToString(", ")).append('\n')
        append("\n# The collected text of this item\n")
        records.forEach { r ->
            append("\n[").append(r.ref).append("] source: ").append(r.sourceName).append('\n')
            append("title: ").append(r.record.title).append('\n')
            append("link: ").append(r.record.link).append('\n')
            if (r.record.date.isNotBlank()) append("date: ").append(r.record.date).append('\n')
            r.record.signal?.let { append("signal: ").append(it).append('\n') }
            append("text:\n").append(clip(r.record.rawText, WRITE_TEXT_CHARS)).append('\n')
            if (r.record.link in pages) {
                val page = pages[r.record.link]
                append("\n## Primary page <").append(r.record.link).append(">\n")
                append(if (page.isNullOrBlank()) "(it could not be read: say so in a note block; state nothing from it)" else clip(page, PAGE_CHARS))
                append('\n')
            }
        }
        val links = records.flatMap { it.links }.distinct()
        val media = records.flatMap { it.record.media }.distinct()
        append("\n# Links this item may cite (sources block, quote url)\n")
        links.forEach { append("- ").append(it).append('\n') }
        append("\n# Media addresses this item may embed (image src, video url)\n")
        if (media.isEmpty()) append("(none: no image or video block)\n")
        media.forEach { append("- ").append(it).append('\n') }
        if (previous != null) {
            append("\n# This item is an update\n")
            append("Its link was published before, as this item:\n").append(clip(previous, WRITE_TEXT_CHARS)).append('\n')
            append("Write it only as an update: put what changed since then, in one or two sentences, in the field ")
            append("\"changed\". If nothing new is in the collected text, answer {\"skip\": \"<reason>\"}.\n")
        }
        append("\n# What to answer\n")
        append("A JSON object: ")
        append("""{"id": "short-slug", "kind": "$kind", """)
        if (previous != null) append(""""changed": "...", """)
        append(""""blocks": [{"type": "title", "kicker": "...", "text": "..."}, ..., """)
        append("""{"type": "sources", "links": [{"label": "...", "url": "..."}]}]}""").append('\n')
        append("Every block must match the schema above. When, after reading, there is nothing good to write, answer ")
        append("""{"skip": "<reason>"}""").append(" with the reason in ").append(Brief.languageName(brief.magazine.language)).append(".\n")
    }

    fun reformat(brief: Brief, schema: JsonObject, accepted: List<String>, item: Item): String = buildString {
        append(brief.render())
        append("\n# The current format of this magazine: the JSON Schema of one item\n")
        append(DobraJson.encodeToString(JsonObject.serializer(), schema)).append('\n')
        append("Accepted block types: ").append(accepted.joinToString(", ")).append('\n')
        append("\n# The item to rewrite\n")
        append(DobraJson.encodeToString(Item.serializer(), item)).append('\n')
        append("\n# What to answer\n")
        append("""A JSON object: {"id": "${item.id}", "kind": "${item.kind.name.lowercase()}", "blocks": [...]}""")
        append(" whose blocks match the schema above.\n")
    }

    fun schemaOf(brief: Brief): JsonObject = ItemFormat.schemaOf(brief.magazine.format, brief.magazine.name)

    private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + " [...]"
}
