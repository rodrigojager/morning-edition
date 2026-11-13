package dobra.make

import dobra.ai.provider.AiException
import dobra.ai.provider.AiGateway
import dobra.ai.provider.UsageLedger
import dobra.ai.provider.UsageTotal
import dobra.ai.rewrite.Brief
import dobra.ai.rewrite.ItemRules
import dobra.ai.rewrite.LoopResult
import dobra.ai.rewrite.Prompts
import dobra.ai.rewrite.RewriteLoop
import dobra.ai.rewrite.Verification
import dobra.format.ItemFormat
import dobra.model.Edition
import dobra.model.LeftOut
import dobra.model.Magazine
import dobra.store.EditionRepository
import kotlinx.coroutines.CancellationException

/**
 * The end of a rewrite for the current format. [rewritten] are the ids of the items the AI rewrote and that passed
 * the checks; [kept] are the items that kept their old text (still drawn with only the accepted blocks), with why.
 */
data class ReformatResult(
    val edition: Edition?,
    val outside: Int,
    val rewritten: List<String>,
    val kept: List<LeftOut>,
    val cost: UsageTotal,
)

/**
 * The format editor's explicit action: the AI rewrites the items of an edition that are outside the magazine's
 * current format, keeping their facts and links. Each answer is checked like a new item (format and verification
 * rule, with the addresses the item already had); one that never passes leaves the old item as it was. The edition
 * is stored again with the current recipe version, and the cost lands on its key.
 */
class EditionReformatter(
    private val gateway: AiGateway,
    private val ledger: UsageLedger,
    private val editions: EditionRepository,
    private val maxAttempts: Int = RewriteLoop.DEFAULT_ATTEMPTS,
) {
    private val loop = RewriteLoop(gateway, maxAttempts)

    suspend fun reformat(magazine: Magazine, editionKey: String, onProgress: (MakeProgress) -> Unit = {}): ReformatResult {
        val spent = ledger.spentSince(editionKey)
        val edition = editions.get(editionKey) ?: return ReformatResult(null, 0, emptyList(), emptyList(), spent())
        val format = magazine.format
        val outside = edition.items.filter { !ItemFormat.check(it, format).ok }
        if (outside.isEmpty()) return ReformatResult(edition, 0, emptyList(), emptyList(), spent())
        val brief = Brief(magazine)
        val schema = Prompts.schemaOf(brief)
        val rewritten = LinkedHashMap<String, dobra.model.Item>()
        val kept = ArrayList<LeftOut>()
        outside.forEachIndexed { index, item ->
            val title = ItemRules.titleOf(item.blocks) ?: item.id
            onProgress(MakeProgress(MakeStage.WRITING, index, outside.size, title))
            val (links, media) = Verification.addressesOf(item)
            val outcome = try {
                loop.run(Prompts.REFORMAT_SYSTEM, Prompts.reformat(brief, schema, format.types, item), editionKey) { text ->
                    ItemRules.judge(text, format, links, media, item.kind, update = false, fixedId = item.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException.KeyRefused) {
                throw e
            } catch (e: AiException.NotConfigured) {
                throw e
            } catch (e: AiException) {
                kept += LeftOut(title, "A chamada à IA falhou: ${e.message}.")
                return@forEachIndexed
            }
            when (outcome) {
                is LoopResult.Done -> rewritten[item.id] = outcome.value
                is LoopResult.Skipped -> kept += LeftOut(title, outcome.reason)
                is LoopResult.Failed -> kept += LeftOut(
                    title,
                    "Depois de ${outcome.attempts.size} tentativas o texto ainda não passava na verificação " +
                        "(${outcome.faults.joinToString("; ")}); ficou o texto anterior.",
                )
            }
        }
        onProgress(MakeProgress(MakeStage.SAVING, 0, 1))
        val next = edition.copy(
            items = edition.items.map { rewritten[it.id] ?: it },
            recipeVersion = if (rewritten.isEmpty()) edition.recipeVersion else magazine.version,
        )
        if (rewritten.isNotEmpty()) editions.put(next)
        onProgress(MakeProgress(MakeStage.SAVING, 1, 1))
        return ReformatResult(next, outside.size, rewritten.keys.toList(), kept, spent())
    }
}
