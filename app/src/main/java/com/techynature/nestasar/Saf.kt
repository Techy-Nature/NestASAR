package com.techynature.nestasar

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import com.techynature.nestasar.asar.AsarExtractor
import com.techynature.nestasar.asar.AsarPacker
import com.techynature.nestasar.asar.ByteSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Storage Access Framework glue: no storage permissions needed, works with any folder the user picks. */
object Saf {
    const val BINARY = "application/octet-stream"

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "archive.asar"
    }

    /**
     * Opens a picked file for random access. Most providers give a seekable file descriptor;
     * if this one doesn't (cloud files, some file managers), the file is copied to the app cache first.
     */
    fun openSource(ctx: Context, uri: Uri): ByteSource {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("The file couldn't be opened.")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val channel = stream.channel
        val seekable = try {
            channel.size() > 0 && channel.read(ByteBuffer.allocate(4), 0) >= 0
        } catch (_: Exception) {
            false
        }
        if (seekable) return ByteSource.of(channel, stream)
        stream.close()

        val copy = File(ctx.cacheDir, "opened.asar")
        copy.delete()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("The file couldn't be read.")
        input.use { i -> copy.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        val raf = RandomAccessFile(copy, "r")
        return ByteSource.of(raf.channel, raf)
    }

    /** Document URI for the folder a tree URI points at. */
    fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun createFolder(cr: ContentResolver, parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(cr, parent, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Couldn't create the folder \"$name\".")

    fun docName(ctx: Context, doc: Uri): String {
        try {
            ctx.contentResolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return "folder"
    }

    fun documentIdOrNull(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) {
        null
    }

    fun deleteQuietly(ctx: Context, doc: Uri) {
        try {
            DocumentsContract.deleteDocument(ctx.contentResolver, doc)
        } catch (_: Exception) {
        }
    }
}

/** Writes extracted entries under a picked folder. Remembers every folder it creates so lookups stay fast. */
class SafSink(ctx: Context, root: Uri) : AsarExtractor.Sink {
    private val cr = ctx.contentResolver
    private val folders = HashMap<String, Uri>().apply { put("", root) }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")
    private fun nameOf(path: String) = path.substringAfterLast('/')

    private fun folder(path: String): Uri {
        folders[path]?.let { return it }
        makeDir(path)
        return folders.getValue(path)
    }

    override fun makeDir(path: String) {
        if (folders.containsKey(path)) return
        val parent = folder(parentOf(path))
        folders[path] = Saf.createFolder(cr, parent, nameOf(path))
    }

    override fun createFile(path: String): OutputStream {
        val parent = folder(parentOf(path))
        val doc = DocumentsContract.createDocument(cr, parent, Saf.BINARY, nameOf(path))
            ?: throw IOException("Couldn't create \"$path\".")
        return cr.openOutputStream(doc, "w") ?: throw IOException("Couldn't write \"$path\".")
    }
}

/** A picked folder (or something inside it) as packer input. */
class SafNode(
    private val ctx: Context,
    private val tree: Uri,
    private val docId: String,
    private val name: String,
    private val dir: Boolean,
    private val skipDocIds: Set<String>,
) : AsarPacker.Node {

    override fun name(): String = name

    override fun isDirectory(): Boolean = dir

    override fun children(): List<AsarPacker.Node> {
        val out = ArrayList<AsarPacker.Node>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = ctx.contentResolver.query(uri, cols, null, null, null)
            ?: throw IOException("Couldn't list the folder \"$name\".")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                if (id in skipDocIds) continue
                val childName = c.getString(1) ?: continue
                out.add(SafNode(ctx, tree, id, childName, c.getString(2) == Document.MIME_TYPE_DIR, skipDocIds))
            }
        }
        return out
    }

    override fun open(): InputStream =
        ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            ?: throw IOException("Couldn't read \"$name\".")

    companion object {
        fun root(ctx: Context, tree: Uri, skipDocIds: Set<String>): SafNode {
            val id = DocumentsContract.getTreeDocumentId(tree)
            return SafNode(ctx, tree, id, Saf.docName(ctx, Saf.treeRoot(tree)), true, skipDocIds)
        }
    }
}
