package dobra.store

import java.io.File

private class JavaEditionFiles(private val root: File) : EditionFiles {
    override fun read(path: String): String? = File(root, path).takeIf { it.isFile }?.readText()

    override fun write(path: String, text: String) {
        val target = File(root, path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    override fun delete(path: String) {
        File(root, path).delete()
    }
}

actual fun editionFilesIn(root: String): EditionFiles {
    val dir = File(root)
    dir.mkdirs()
    return JavaEditionFiles(dir)
}

actual fun pathIn(root: String, name: String): String = File(root, name).path
