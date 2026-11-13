package dobra.make

import dobra.ai.ask.StoryReference
import dobra.ai.provider.AiException
import dobra.ai.provider.AiGateway
import dobra.ai.provider.UsageLedger
import dobra.ai.provider.UsageTotal
import dobra.ai.rewrite.Answers
import dobra.ai.rewrite.Attempt
import dobra.ai.rewrite.Brief
import dobra.ai.rewrite.ItemRules
import dobra.ai.rewrite.LoopResult
import dobra.ai.rewrite.Prompts
import dobra.ai.rewrite.RecordRef
import dobra.ai.rewrite.RewriteLoop
import dobra.ai.rewrite.Verdict
import dobra.ai.rewrite.Verification
import dobra.collect.worker.EditionLinks
import dobra.collect.worker.MagazineCollector
import dobra.collect.worker.WorkerResult
import dobra.model.Edition
import dobra.model.EditionState
import dobra.model.Item
import dobra.model.ItemKind
import dobra.model.LeftOut
import dobra.model.Magazine
import dobra.store.EditionRepository
import dobra.store.Feedback
import dobra.store.Keys
import dobra.store.MarksRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class MakeStage { COLLECTING, CHOOSING, WRITING, SAVING }

/** Where a run stands: [done] of [total] steps of [stage]; [current] names what is being worked on. */
data class MakeProgress(val stage: MakeStage, val done: Int, val total: Int, val current: String? = null)

enum class MakeProblem {
    /** The sources brought no record to write from. */
    NOTHING_COLLECTED,

    /** The AI kept nothing, or nothing it wrote passed the checks: no edition was stored. */
    NOTHING_WRITTEN,

    /** The AI's selection could not be read after every attempt. */
    SELECTION_UNREADABLE,

    /** The store refused the edition (the reader deleted an edition with that key). */
    REFUSED_BY_STORE,
}

/**
 * The end of a run. [edition] is what was stored, or null with the [problem]. [leftOut] lists what was considered and
 * not included, with the reason; [cost] sums every AI call of this run (the ledger keeps the edition's total under its
 * key); [attempts] are those calls, the selection first.
 */
data class MakeResult(
    val edition: Edition?,
    val leftOut: List<LeftOut>,
    val cost: UsageTotal,
    val considered: Int,
    val problem: MakeProblem? = null,
    val attempts: List<Attempt> = emptyList(),
)

/** One item the AI chose to write, from one or more records. */
internal data class Pick(val refs: List<RecordRef>, val kind: ItemKind, val update: Boolean)

internal data class Selection(val keep: List<Pick>, val leftOut: List<LeftOut>)

/**
 * Job B, filter and rewrite (PLAN-ai): from the raw records of a run the AI keeps what is relevant, each kept item is
 * written as blocks in the magazine's language and tone, checked against the format and the verification rule (sent
 * back with the validator's messages up to [maxAttempts] calls), and the edition is stored, unread, with its number,
 * date, recipe version, collection record and what was left out. Its AI cost is in the ledger under its key.
 */
class EditionMaker(
    private val gateway: AiGateway,
    private val ledger: UsageLedger,
    private val editions: EditionRepository,
    private val marks: MarksRepository? = null,
    private val pages: PageReader? = null,
    /** Today's date as `YYYY-MM-DD`. */
    private val today: () -> String,
    private val maxAttempts: Int = RewriteLoop.DEFAULT_ATTEMPTS,
    private val maxItems: Int = MAX_ITEMS,
) {
    private val loop = RewriteLoop(gateway, maxAttempts)

    suspend fun make(magazine: Magazine, run: WorkerResult, onProgress: (MakeProgress) -> Unit = {}): MakeResult {
        val date = freeDate(magazine.id, today())
        val key = Keys.edition(magazine.id, date)
        val spent = ledger.spentSince(key)
        val stored = editions.list(magazine.id).mapNotNull { editions.get(it.key) }
        val brief = briefOf(magazine, stored)
        val records = recordsOf(magazine, run, brief.published)
        if (records.isEmpty()) return MakeResult(null, emptyList(), spent(), 0, MakeProblem.NOTHING_COLLECTED)

        // 1. Filter.
        onProgress(MakeProgress(MakeStage.CHOOSING, 0, 1))
        val byRef = records.associateBy { it.ref }
        val chosen = loop.run(Prompts.FILTER_SYSTEM, Prompts.filter(brief, records, maxItems), key) { judgeSelection(it, byRef) }
        val attempts = ArrayList(chosen.attempts)
        val selection = when (chosen) {
            is LoopResult.Done -> chosen.value
            else -> return MakeResult(null, emptyList(), spent(), records.size, MakeProblem.SELECTION_UNREADABLE, attempts)
        }
        val leftOut = ArrayList(selection.leftOut)
        val picks = selection.keep.take(maxItems)
        selection.keep.drop(maxItems).forEach { leftOut += leftOutOf(it, "Ficou de fora para a edição não passar de $maxItems matérias.") }

        // 2-3. Read the primary page and write each kept item.
        val items = ArrayList<Item>()
        picks.forEachIndexed { index, pick ->
            onProgress(MakeProgress(MakeStage.WRITING, index, picks.size, pick.refs.first().record.title))
            val outcome = try {
                write(brief, pick, stored, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException.KeyRefused) {
                throw e
            } catch (e: AiException.NotConfigured) {
                throw e
            } catch (e: AiException) {
                leftOut += leftOutOf(pick, "Ficou de fora porque a chamada à IA falhou: ${e.message}.")
                return@forEachIndexed
            }
            attempts += outcome.attempts
            when (outcome) {
                is LoopResult.Done -> items += outcome.value.copy(id = uniqueId(outcome.value.id, items))
                is LoopResult.Skipped -> leftOut += leftOutOf(pick, outcome.reason)
                is LoopResult.Failed -> leftOut += leftOutOf(
                    pick,
                    "Ficou de fora: depois de ${outcome.attempts.size} tentativas o texto ainda não passava na " +
                        "verificação do formato (${outcome.faults.joinToString("; ")}).",
                )
            }
        }
        onProgress(MakeProgress(MakeStage.WRITING, picks.size, picks.size))
        if (items.isEmpty()) {
            return MakeResult(null, leftOut, spent(), records.size, MakeProblem.NOTHING_WRITTEN, attempts)
        }

        // 4. Store the edition with what was left out.
        onProgress(MakeProgress(MakeStage.SAVING, 0, 1))
        val edition = Edition(
            magazine = magazine.id,
            date = date,
            number = (stored.maxOfOrNull { it.number } ?: 0) + 1,
            recipeVersion = magazine.version,
            items = items,
            collection = run.collection,
            leftOut = leftOut,
            state = EditionState.PUBLISHED,
        )
        val ok = editions.put(edition)
        onProgress(MakeProgress(MakeStage.SAVING, 1, 1))
        return MakeResult(
            if (ok) edition else null, leftOut, spent(), records.size,
            if (ok) null else MakeProblem.REFUSED_BY_STORE, attempts,
        )
    }

    private suspend fun write(brief: Brief, pick: Pick, stored: List<Edition>, key: String): LoopResult<Item> {
        val texts = LinkedHashMap<String, String?>()
        if (pages != null) {
            for (r in pick.refs) {
                texts[r.record.link] = try {
                    pages.read(r.record.link)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
        val previous = if (pick.update) previousOf(pick, stored) else null
        val format = brief.magazine.format
        val prompt = Prompts.write(
            brief, Prompts.schemaOf(brief), format.types, pick.refs, texts,
            if (pick.kind == ItemKind.NOTE) "note" else "story",
            previous ?: if (pick.update) "(the earlier item is no longer stored)" else null,
        )
        val links = pick.refs.flatMap { it.links }.toSet()
        val media = pick.refs.flatMap { it.record.media }.toSet()
        return loop.run(Prompts.WRITE_SYSTEM, prompt, key) { text ->
            when (val v = ItemRules.judge(text, format, links, media, pick.kind, pick.update)) {
                is Verdict.Ok -> Verdict.Ok(v.value.copy(id = ItemRules.slug(v.value.id)))
                else -> v
            }
        }
    }

    /** The text of the item that published the pick's link before, for the update to say what changed. */
    private fun previousOf(pick: Pick, stored: List<Edition>): String? {
        val wanted = pick.refs.map { Verification.normalize(it.record.link) }.toSet()
        for (e in stored) for (item in e.items) {
            val (links, _) = Verification.addressesOf(item)
            if (links.any { Verification.normalize(it) in wanted }) {
                return "(edition ${e.number}, ${e.date})\n" + StoryReference.of(item).render()
            }
        }
        return null
    }

    private suspend fun briefOf(m: Magazine, stored: List<Edition>): Brief {
        val published = stored.reversed().flatMap { EditionLinks.linksOf(it) }.distinct()
        val liked = ArrayList<String>()
        val disliked = ArrayList<String>()
        marks?.feedbackOf(m.id)?.forEach { (itemKey, feedback) ->
            val editionKey = itemKey.substringBeforeLast('/')
            val id = itemKey.substringAfterLast('/')
            val title = stored.firstOrNull { it.key == editionKey }?.items?.firstOrNull { it.id == id }
                ?.let { ItemRules.titleOf(it.blocks) } ?: return@forEach
            if (feedback == Feedback.MORE) liked += title else disliked += title
        }
        return Brief(
            magazine = m,
            published = published,
            liked = liked.take(MAX_FEEDBACK),
            disliked = disliked.take(MAX_FEEDBACK),
            interestsOff = marks?.interestsOff(m.id).orEmpty(),
        )
    }

    private fun recordsOf(m: Magazine, run: WorkerResult, published: List<String>): List<RecordRef> {
        val done = published.map(Verification::normalize).toSet()
        val urlById = m.sources.associate { it.id to it.url }
        val urlByName = m.sources.associate { it.name to it.url }
        val fresh = run.records.map {
            RecordRef("", it.sourceName, urlById[it.sourceId], it.record, Verification.normalize(it.record.link) in done)
        }
        val again = run.candidates.map { RecordRef("", it.source, urlByName[it.source], it.record, publishedBefore = true) }
        return (fresh + again)
            .distinctBy { Verification.normalize(it.record.link) }
            .take(MAX_RECORDS)
            .mapIndexed { i, r -> r.copy(ref = "r${i + 1}") }
    }

    private suspend fun freeDate(magazine: String, day: String): String {
        var date = day
        var n = 1
        while (editions.get(Keys.edition(magazine, date)) != null || editions.isDeleted(Keys.edition(magazine, date))) {
            n++
            date = "$day-$n"
        }
        return date
    }

    private fun uniqueId(id: String, taken: List<Item>): String {
        val ids = taken.map { it.id }.toSet()
        if (id !in ids) return id
        var n = 2
        while ("$id-$n" in ids) n++
        return "$id-$n"
    }

    private fun leftOutOf(pick: Pick, reason: String): LeftOut {
        val r = pick.refs.first().record
        return LeftOut(r.title, reason, r.link)
    }

    companion object {
        /** Three to eight good items are better than filling a quota (PLAN-ai). */
        const val MAX_ITEMS = 8

        /** Records sent to the selection, at most; the worker's order is kept. */
        const val MAX_RECORDS = 80
        const val MAX_FEEDBACK = 10

        /** Reads the selection answer: `{"keep": [{"records": [...], "kind", "update"}], "leftOut": [...]}`. */
        internal fun judgeSelection(text: String, byRef: Map<String, RecordRef>): Verdict<Selection> {
            val o = Answers.objectOf(text) ?: return Verdict.Faults(listOf("the answer is not a JSON object"))
            val keep = o["keep"] as? JsonArray
                ?: return Verdict.Faults(listOf("the field \"keep\" is missing or is not an array (use [] to keep nothing)"))
            val faults = ArrayList<String>()
            val used = HashSet<String>()
            val picks = ArrayList<Pick>()
            keep.forEachIndexed { i, e ->
                val entry = e as? JsonObject
                if (entry == null) {
                    faults += "keep entry ${i + 1} is not a JSON object"
                    return@forEachIndexed
                }
                val refs = (entry["records"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: listOfNotNull(Answers.string(entry, "record"))
                val unknown = refs.filter { it !in byRef }
                when {
                    refs.isEmpty() -> faults += "keep entry ${i + 1} names no record"
                    unknown.isNotEmpty() -> faults += "keep entry ${i + 1} names records that are not in the list: ${unknown.joinToString(", ")}"
                    else -> {
                        val fresh = refs.distinct().filter { used.add(it) }.map { byRef.getValue(it) }
                        if (fresh.isNotEmpty()) {
                            val update = (entry["update"] as? JsonPrimitive)?.booleanOrNull == true || fresh.any { it.publishedBefore }
                            val kind = if (Answers.string(entry, "kind") == "note") ItemKind.NOTE else ItemKind.STORY
                            picks += Pick(fresh, kind, update)
                        }
                    }
                }
            }
            if (faults.isNotEmpty()) return Verdict.Faults(faults)
            val leftOut = (o["leftOut"] as? JsonArray).orEmpty().mapNotNull { e ->
                val entry = e as? JsonObject ?: return@mapNotNull null
                val reason = Answers.string(entry, "reason") ?: return@mapNotNull null
                val record = Answers.string(entry, "record")?.let { byRef[it] }?.record
                val title = record?.title ?: Answers.string(entry, "title") ?: return@mapNotNull null
                if (record != null && Answers.string(entry, "record") in used) return@mapNotNull null
                LeftOut(title, reason, record?.link)
            }
            return Verdict.Ok(Selection(picks, leftOut))
        }
    }
}

/** "Gerar edição agora": the worker's run, then the edition made from it. */
class EditionPipeline(private val collector: MagazineCollector, private val maker: EditionMaker) {
    suspend fun run(magazine: Magazine, onProgress: (MakeProgress) -> Unit = {}): MakeResult {
        val run = collector.collect(magazine) { onProgress(MakeProgress(MakeStage.COLLECTING, it.done, it.total, it.current)) }
        return maker.make(magazine, run, onProgress)
    }
}
