package dobra.app.library

import dobra.model.Cadence
import dobra.model.CadenceKind
import dobra.model.Magazine
import dobra.model.Source
import dobra.model.Topic
import java.net.URI
import java.text.Normalizer

/** What the recipe form holds while the reader types. Plain text, so it survives recreation. */
data class RecipeDraft(
    val name: String = "",
    val about: String = "",
    val subjects: String = "",
    val keywords: String = "",
    val language: String = "pt-BR",
    val tone: String = "didatico",
    val prompt: String = "",
    val weekly: Boolean = false,
    val hour: String = "7",
) {
    /** The hour as typed, or null when it is not a whole number from 0 to 23. */
    val hourValue: Int? get() = hour.trim().toIntOrNull()?.takeIf { it in 0..23 }

    val valid: Boolean get() = name.isNotBlank() && hourValue != null

    companion object {
        fun of(m: Magazine) = RecipeDraft(
            name = m.name,
            about = m.about,
            subjects = m.topics.joinToString(", ") { it.name },
            keywords = m.keywords.joinToString(", "),
            language = m.language,
            tone = m.tone,
            prompt = m.prompt,
            weekly = m.cadence.kind == CadenceKind.WEEKLY,
            hour = m.cadence.hour.toString(),
        )
    }
}

/** Splits a comma separated field into trimmed, non-empty, non-repeated entries. */
fun splitList(text: String): List<String> =
    text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** A lowercase, accent-free id made of letters, digits and hyphens. */
fun slugOf(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "revista" }

fun uniqueId(base: String, taken: Collection<String>): String {
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

/**
 * Applies the form to a magazine: [base] when editing (everything the form does not show is kept: format, looks,
 * sources), or a new recipe with [newId]. The version is left to the store, which raises it when the content changed.
 */
fun RecipeDraft.applyTo(base: Magazine?, newId: String): Magazine {
    val old = base ?: Magazine(id = newId, name = name)
    val weights = old.topics.associate { it.name to it.weight }
    val kind = if (weekly) CadenceKind.WEEKLY else CadenceKind.DAILY
    return old.copy(
        name = name.trim(),
        about = about.trim(),
        topics = splitList(subjects).map { Topic(it, weights[it] ?: 1.0) },
        keywords = splitList(keywords),
        language = language,
        tone = tone,
        prompt = prompt.trim(),
        cadence = Cadence(kind, hourValue ?: old.cadence.hour, if (kind == CadenceKind.WEEKLY) old.cadence.weekday ?: 1 else null),
    )
}

/** True for an address that starts with http:// or https:// and names a host. */
fun isWebAddress(text: String): Boolean {
    val address = text.trim()
    if (address.any { it.isWhitespace() }) return false
    val uri = runCatching { URI(address) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
}

/** The record of a new source: no collector yet, so it is unavailable until one exists. */
fun newSource(magazine: Magazine, name: String, url: String, liked: String): Source {
    val address = url.trim()
    val label = name.trim().ifEmpty { URI(address).host.removePrefix("www.") }
    return Source(
        id = uniqueId(slugOf(label), magazine.sources.map { it.id }),
        name = label,
        url = address,
        liked = liked.trim(),
    )
}
