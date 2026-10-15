package dev.abhay.assistant

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/**
 * Debug builds only: a flat folder of files in the app's cache, served as a document tree, so the
 * device tests can exercise the notes folder (SafNotes, the Storage Access Framework) without the
 * system picker and without touching the user's vault. It lists no roots, so the picker never shows
 * it. The app reaches it as its own provider, so no grant is needed.
 */
class TestDocumentsProvider : DocumentsProvider() {
    private val dir get() = File(context!!.cacheDir, "testdocs").apply { mkdirs() }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { row(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: COLUMNS).apply { dir.listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, it.name) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(dir, documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        File(dir, displayName).createNewFile()
        return displayName
    }

    override fun deleteDocument(documentId: String) {
        File(dir, documentId).delete()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == ROOT

    private fun row(cursor: MatrixCursor, id: String) {
        val file = if (id == ROOT) dir else File(dir, id)
        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to id,
            Document.COLUMN_DISPLAY_NAME to if (id == ROOT) "testdocs" else file.name,
            Document.COLUMN_MIME_TYPE to if (id == ROOT) Document.MIME_TYPE_DIR else "text/markdown",
            Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            Document.COLUMN_SIZE to file.length(),
            Document.COLUMN_FLAGS to (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_DIR_SUPPORTS_CREATE),
        )
        cursor.addRow(cursor.columnNames.map { values[it] })
    }

    companion object {
        const val AUTHORITY = "dev.abhay.assistant.testdocs"
        const val ROOT = "root"
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
    }
}
