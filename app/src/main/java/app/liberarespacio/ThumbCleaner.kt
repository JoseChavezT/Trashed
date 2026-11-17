package app.liberarespacio

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject

/**
 * Busca y borra carpetas de miniaturas (.thumbnails, thumbs, ...) dentro de una carpeta que el
 * usuario eligió con el selector del sistema (Storage Access Framework). No necesita acceso a
 * todos los archivos: solo ve lo que hay dentro de esa carpeta.
 *
 * El borrado es definitivo (los documentos no van a la papelera), pero son cachés que el
 * sistema o la app que las creó regeneran cuando hacen falta.
 */
class ThumbCleaner(private val context: Context, private val tree: Uri) {

    private data class Doc(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private class Folder(val path: String) {
        val files = mutableListOf<Doc>()
        val bytes get() = files.sumOf { it.size }
    }

    companion object {
        private val THUMB_DIR = Regex("^\\.?thumb(nail)?s?$", RegexOption.IGNORE_CASE)
        private const val MAX_DEPTH = 4
    }

    private val cr get() = context.contentResolver

    private fun children(docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = mutableListOf<Doc>()
        cr.query(
            uri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out += Doc(
                    id = c.getString(0),
                    name = c.getString(1) ?: "",
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3)
                )
            }
        }
        return out
    }

    /** Todos los archivos bajo una carpeta de miniaturas, en cualquier profundidad. */
    private fun collect(docId: String, into: Folder) {
        for (d in children(docId)) {
            if (d.isDir) collect(d.id, into) else into.files += d
        }
    }

    private fun find(): List<Folder> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootId.substringAfterLast(':').substringAfterLast('/')
        val found = mutableListOf<Folder>()
        if (THUMB_DIR.matches(rootName)) {
            val f = Folder(rootName)
            collect(rootId, f)
            found += f
            return found
        }
        fun walk(docId: String, path: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (d in children(docId)) {
                if (!d.isDir) continue
                val p = if (path.isEmpty()) d.name else "$path/${d.name}"
                if (THUMB_DIR.matches(d.name)) {
                    val f = Folder(p)
                    collect(d.id, f)
                    if (f.files.isNotEmpty()) found += f
                } else {
                    walk(d.id, p, depth + 1)
                }
            }
        }
        walk(rootId, rootName, 1)
        return found
    }

    fun scan(): JSONObject {
        val folders = find()
        return JSONObject()
            .put("tree", tree.toString())
            .put("root", DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "/" })
            .put("folders", JSONArray().apply {
                folders.forEach { put(JSONObject().put("path", it.path).put("files", it.files.size).put("bytes", it.bytes)) }
            })
            .put("files", folders.sumOf { it.files.size })
            .put("bytes", folders.sumOf { it.bytes })
    }

    fun delete(progress: (Int, Int) -> Unit): JSONObject {
        val files = find().flatMap { it.files }
        var deleted = 0
        var bytes = 0L
        var failed = 0
        var last = 0L
        files.forEachIndexed { i, d ->
            val ok = runCatching {
                DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, d.id))
            }.getOrDefault(false)
            if (ok) { deleted++; bytes += d.size } else failed++
            val now = System.currentTimeMillis()
            if (now - last > 250 || i == files.size - 1) { last = now; progress(i + 1, files.size) }
        }
        return JSONObject().put("deleted", deleted).put("bytes", bytes).put("failed", failed)
    }
}
