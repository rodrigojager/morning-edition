package dobra.store

/** Where the JSON files of the editions live. Paths are relative to the store's folder and use `/`. */
interface EditionFiles {
    fun read(path: String): String?
    fun write(path: String, text: String)
    fun delete(path: String)
}

/** Creates the folder [root] if needed and returns file access inside it. */
expect fun editionFilesIn(root: String): EditionFiles

/** Absolute path of a file inside [root]. */
expect fun pathIn(root: String, name: String): String
