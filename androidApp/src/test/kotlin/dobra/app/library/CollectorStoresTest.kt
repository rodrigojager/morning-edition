package dobra.app.library

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dobra.ai.collector.Fixture
import dobra.ai.collector.SavedPage
import dobra.collect.health.HealthRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

/** The app keeps the saved copies of pages and the health records between starts. */
@RunWith(AndroidJUnit4::class)
class CollectorStoresTest {
    @Test
    fun savedCopiesSurviveAndAreDeletedByKey() = runBlocking {
        val dir = Files.createTempDirectory("dobra-pages").toFile()
        try {
            val copy = Fixture("https://www.example.org/", "2026-11-13T07:00:00Z", listOf(SavedPage("https://www.example.org/", 200, "<p>olá</p>", "text/html")))
            FileFixtureStore(dir).put("tec/exemplo/1", copy)
            assertEquals(copy, FileFixtureStore(dir).get("tec/exemplo/1"))
            assertNull(FileFixtureStore(dir).get("tec/exemplo/2"))
            FileFixtureStore(dir).delete("tec/exemplo/1")
            assertNull(FileFixtureStore(dir).get("tec/exemplo/1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun healthRecordsSurvive() = runBlocking {
        val record = HealthRecord(badRuns = 2, refusals = 1, hadRecords = true, note = "motivo")
        PrefsHealthStore(ApplicationProvider.getApplicationContext()).put("tec", "exemplo", record)
        assertEquals(record, PrefsHealthStore(ApplicationProvider.getApplicationContext()).get("tec", "exemplo"))
        assertEquals(HealthRecord(), PrefsHealthStore(ApplicationProvider.getApplicationContext()).get("tec", "outra"))
    }
}
