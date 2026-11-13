package dobra.seed

import dobra.model.DobraJson
import dobra.model.Edition
import dobra.model.Magazine

/**
 * The demonstration magazine and its edition, shipped as JSON in `shared/seed` (packaged as app assets under the
 * same names). Reading the files is the platform's job; this object only parses their text.
 */
object Seed {
    const val MAGAZINE_FILE = "magazine.json"
    const val EDITION_FILE = "edition.json"

    fun magazine(json: String): Magazine = DobraJson.decodeFromString(Magazine.serializer(), json)

    fun edition(json: String): Edition = DobraJson.decodeFromString(Edition.serializer(), json)
}
