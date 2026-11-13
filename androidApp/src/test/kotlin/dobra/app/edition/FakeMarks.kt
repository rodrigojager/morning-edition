package dobra.app.edition

import dobra.model.Item
import dobra.store.Feedback
import dobra.store.Keys
import dobra.store.MarksRepository
import dobra.store.SavedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory marks for screen tests: same contract as the store's, nothing on disk. */
class FakeMarks : MarksRepository {
    private val savedState = MutableStateFlow<List<SavedItem>>(emptyList())
    val read = linkedSetOf<String>()
    val feedbacks = linkedMapOf<String, Feedback>()

    val savedKeys: List<String> get() = savedState.value.map { it.key }

    override fun observeSaved(): Flow<List<SavedItem>> = savedState
    override suspend fun saved(): List<SavedItem> = savedState.value
    override suspend fun isSaved(itemKey: String) = savedState.value.any { it.key == itemKey }

    override suspend fun save(editionKey: String, item: Item) {
        val key = Keys.item(editionKey, item.id)
        savedState.value = listOf(SavedItem(key, item)) + savedState.value.filter { it.key != key }
    }

    override suspend fun unsave(itemKey: String) {
        savedState.value = savedState.value.filter { it.key != itemKey }
    }

    override suspend fun setStoryRead(itemKey: String, read: Boolean) {
        if (read) this.read += itemKey else this.read -= itemKey
    }

    override suspend fun readStories(editionKey: String): Set<String> = read.filter { it.startsWith("$editionKey/") }.toSet()
    override suspend fun setEditionRead(editionKey: String, read: Boolean) {}
    override suspend fun isEditionRead(editionKey: String) = false
    override suspend fun setOrder(editionKey: String, itemIds: List<String>?) {}
    override suspend fun order(editionKey: String): List<String>? = null

    override suspend fun setFeedback(itemKey: String, feedback: Feedback?) {
        if (feedback == null) feedbacks.remove(itemKey) else feedbacks[itemKey] = feedback
    }

    override suspend fun feedback(itemKey: String): Feedback? = feedbacks[itemKey]
    override suspend fun feedbackOf(magazine: String): Map<String, Feedback> =
        feedbacks.filterKeys { it.startsWith("$magazine/") }

    override suspend fun setInterestOff(magazine: String, topic: String, off: Boolean) {}
    override suspend fun interestsOff(magazine: String): Set<String> = emptySet()
}
