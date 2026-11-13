package dobra.app.edition

import dobra.model.Edition
import dobra.model.Magazine
import dobra.seed.Seed
import java.io.File

/** The demonstration magazine and edition, read from the seed folder (the unit tests run in `androidApp/`). */
object EditionFixture {
    private fun seed(name: String) = File("../shared/seed/$name").readText()

    val magazine: Magazine by lazy { Seed.magazine(seed(Seed.MAGAZINE_FILE)) }
    val edition: Edition by lazy { Seed.edition(seed(Seed.EDITION_FILE)) }
}
