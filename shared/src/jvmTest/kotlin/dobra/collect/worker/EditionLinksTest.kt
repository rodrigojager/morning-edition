package dobra.collect.worker

import dobra.seed.Seed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class EditionLinksTest {
    @Test
    fun theDemonstrationEditionPublishedItsSourceLinks() {
        val edition = Seed.edition(File("seed", Seed.EDITION_FILE).readText())
        val links = EditionLinks.linksOf(edition)
        assertTrue("https://github.com/cloudflare/security-audit-skill" in links, links.toString())
        assertTrue(links.all { it.startsWith("http") })
    }
}
