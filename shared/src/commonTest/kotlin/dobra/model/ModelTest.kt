package dobra.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    private inline fun <reified T> roundTrip(v: T) {
        val s = DobraJson.encodeToString(kotlinx.serialization.serializer<T>(), v)
        assertEquals(v, DobraJson.decodeFromString(kotlinx.serialization.serializer<T>(), s))
    }

    @Test
    fun magazineRoundTrips() {
        val src = Source(
            id = "s1", name = "example.org", url = "https://example.org", liked = "news on x",
            collector = CollectorInfo("function run(){}", 2, "2026-11-01T10:00:00Z", "model-x"),
            fixture = "<html/>", cursor = "c1",
            lastRun = LastRun("2026-11-02T07:00:00Z", 12, CollectionState.PARTIAL),
            health = SourceHealth.WORKING,
        )
        roundTrip(
            Magazine(
                id = "m1", name = "Mag", about = "about",
                topics = listOf(Topic("ai", 0.5)), keywords = listOf("kotlin"),
                tone = "plain", prompt = "p",
                format = Format().without("quote"),
                looks = mapOf("paragraph" to Look(font = "text", size = "1.15", color = "ink", align = "justify")),
                cadence = Cadence(CadenceKind.WEEKLY, 8, weekday = 1),
                sources = listOf(src), version = 3,
            ),
        )
        roundTrip(src)
    }

    @Test
    fun editionRoundTrips() {
        val e = Edition(
            magazine = "m1", date = "2026-11-12", number = 4, recipeVersion = 3,
            items = listOf(Item("i1", ItemKind.NOTE, listOf(Block("note", mapOf("text" to kotlinx.serialization.json.JsonPrimitive("x")))))),
            collection = listOf(CollectionLine("s1", 5, CollectionState.FAILED, "timeout")),
            leftOut = listOf(LeftOut("t", "off topic")),
            state = EditionState.PUBLISHED,
        )
        roundTrip(e)
        assertEquals("m1/2026-11-12", e.key)
    }

    @Test
    fun looksRoundTrip() {
        roundTrip<Looks>(mapOf("title" to Look(font = "display", align = "center"), "code" to Look(font = "mono")))
    }
}
