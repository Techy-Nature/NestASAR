package com.techynature.nestasar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techynature.nestasar.asar.AsarArchive
import com.techynature.nestasar.asar.AsarEntry
import com.techynature.nestasar.asar.AsarException
import com.techynature.nestasar.asar.AsarExtractor
import com.techynature.nestasar.asar.AsarPacker
import com.techynature.nestasar.asar.Hex
import com.techynature.nestasar.asar.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class JobState(
    val title: String,
    val running: Boolean = true,
    val stage: String = "Starting",
    val path: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val failed: Boolean = false,
    val summary: String = "",
    val details: List<String> = emptyList(),
)

sealed class PreviewBody {
    data class Lines(val lines: List<String>, val note: String?) : PreviewBody()
    data class Image(val bitmap: Bitmap, val note: String) : PreviewBody()
    data class Message(val text: String) : PreviewBody()
}

data class Preview(val entry: AsarEntry, val body: PreviewBody?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var archive by mutableStateOf<AsarArchive?>(null)
        private set
    var archiveName by mutableStateOf("")
        private set
    var currentDir by mutableStateOf<AsarEntry?>(null)
    var query by mutableStateOf("")
    var preview by mutableStateOf<Preview?>(null)
    var showInfo by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
        private set
    var job by mutableStateOf<JobState?>(null)
        private set
    var toast by mutableStateOf<String?>(null)

    // options
    var packIntegrity by mutableStateOf(true)
    var packSkipHidden by mutableStateOf(false)
    var verifyOnExtract by mutableStateOf(true)

    // what the next folder/file picker result is for
    var pendingExtract: AsarEntry? = null
    var pendingPackTree: Uri? = null
    var pendingSave: AsarEntry? = null

    private val cancelled = AtomicBoolean(false)

    // ------------------------------------------------------------------ open / close

    /** Opens an archive for browsing. Calls [then] on the main thread if it worked. */
    fun open(uri: Uri, then: (() -> Unit)? = null) {
        viewModelScope.launch {
            busy = "Opening archive…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = Saf.displayName(ctx, uri)
                    val source = Saf.openSource(ctx, uri)
                    try {
                        name to AsarArchive.open(source)
                    } catch (t: Throwable) {
                        source.close()
                        throw t
                    }
                }
            }
            busy = null
            result.onSuccess { (name, opened) ->
                closeArchive()
                archive = opened
                archiveName = name
                currentDir = opened.root()
                query = ""
                then?.invoke()
            }.onFailure {
                toast = "Couldn't open that file: ${it.friendly()}"
            }
        }
    }

    fun closeArchive() {
        val a = archive ?: return
        archive = null
        currentDir = null
        preview = null
        query = ""
        showInfo = false
        try {
            a.close()
        } catch (_: IOException) {
        }
    }

    fun goUp(): Boolean {
        val dir = currentDir ?: return false
        val parent = dir.parent ?: return false
        currentDir = parent
        return true
    }

    // ------------------------------------------------------------------ preview

    fun openPreview(entry: AsarEntry) {
        preview = Preview(entry, null)
        val a = archive ?: return
        viewModelScope.launch {
            val body = withContext(Dispatchers.IO) {
                try {
                    buildPreview(a, entry)
                } catch (t: Throwable) {
                    PreviewBody.Message("Couldn't read this file: ${t.friendly()}")
                }
            }
            if (preview?.entry === entry) preview = Preview(entry, body)
        }
    }

    private fun buildPreview(a: AsarArchive, e: AsarEntry): PreviewBody {
        if (e.isLink) return PreviewBody.Message("This is a symbolic link to:\n${e.link}")
        if (e.unpacked) return PreviewBody.Message(
            "This file isn't stored inside the archive. It lives in the \"${archiveName}.unpacked\" folder next to it."
        )
        if (e.size == 0L) return PreviewBody.Message("This file is empty.")

        val ext = e.name.substringAfterLast('.', "").lowercase()
        if (ext in IMAGE_EXTS && e.size <= 40L * 1024 * 1024) {
            val bytes = a.readHead(e, e.size.toInt())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0) {
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) return PreviewBody.Image(bmp, "${bounds.outWidth} × ${bounds.outHeight} px")
            }
        }

        val head = a.readHead(e, TEXT_LIMIT)
        if (looksLikeText(head)) {
            val text = String(head, Charsets.UTF_8)
            val lines = text.split('\n').map { it.trimEnd('\r') }
            val note = if (e.size > TEXT_LIMIT) "Showing the first ${formatBytes(TEXT_LIMIT.toLong())} of ${formatBytes(e.size)}." else null
            return PreviewBody.Lines(lines, note)
        }

        val hex = a.readHead(e, HEX_LIMIT)
        val note = if (e.size > HEX_LIMIT) "Binary file — showing the first ${formatBytes(HEX_LIMIT.toLong())} as hex." else "Binary file shown as hex."
        return PreviewBody.Lines(hexDump(hex), note)
    }

    // ------------------------------------------------------------------ jobs

    fun cancelJob() = cancelled.set(true)

    fun dismissJob() {
        if (job?.running == false) job = null
    }

    private val progress = object : Progress {
        private var last = 0L
        override fun update(stage: String, path: String, done: Long, total: Long) {
            val now = SystemClock.uptimeMillis()
            if (now - last < 100 && stage != "Done") return
            last = now
            job = job?.copy(stage = stage, path = path, done = done, total = total)
        }

        override fun isCancelled(): Boolean = cancelled.get()
    }

    private fun runJob(title: String, onFailure: () -> Unit = {}, work: (Progress) -> Pair<String, List<String>>) {
        cancelled.set(false)
        job = JobState(title)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { work(progress) }.onFailure { onFailure() }
            }
            job = result.fold(
                onSuccess = { (summary, details) -> job?.copy(running = false, summary = summary, details = details) },
                onFailure = { job?.copy(running = false, failed = true, summary = it.friendly(), details = emptyList()) },
            )
        }
    }

    /** Extracts [pendingExtract] (or the whole archive when it is the root) into a picked folder. */
    fun extractTo(tree: Uri) {
        val a = archive ?: return
        val start = pendingExtract ?: a.root()
        pendingExtract = null
        val verify = verifyOnExtract
        val title = if (start.parent == null) "Extracting $archiveName" else "Extracting ${start.name}"
        runJob(title) { p ->
            val cr = ctx.contentResolver
            var dest = Saf.treeRoot(tree)
            var where = Saf.docName(ctx, dest)
            if (start.parent == null) {
                val folderName = archiveName.removeSuffix(".asar").ifBlank { "extracted" }
                dest = Saf.createFolder(cr, dest, folderName)
                where += "/" + Saf.docName(ctx, dest)
            }
            val r = AsarExtractor.extract(a, start, SafSink(ctx, dest), verify, p)
            val details = ArrayList<String>()
            if (r.integrityFailures.isNotEmpty()) {
                details += "⚠ ${r.integrityFailures.size} file(s) didn't match their stored hash:"
                details += r.integrityFailures.take(MAX_DETAILS).map { "   $it" }
            }
            if (r.skipped.isNotEmpty()) {
                details += "Skipped ${r.skipped.size}:"
                details += r.skipped.take(MAX_DETAILS).map { "   $it" }
                if (r.skipped.size > MAX_DETAILS) details += "   …and ${r.skipped.size - MAX_DETAILS} more"
            }
            "Extracted ${r.files} files and ${r.folders} folders (${formatBytes(r.bytes)}) into $where." to details
        }
    }

    /** Saves one file from the archive to a document the user created. */
    fun saveFile(entry: AsarEntry, target: Uri) {
        val a = archive ?: return
        runJob("Saving ${entry.name}", onFailure = { Saf.deleteQuietly(ctx, target) }) { p ->
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            val out = ctx.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Couldn't write the file.")
            out.use { o ->
                a.openStream(entry).use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        if (p.isCancelled) throw AsarException("Cancelled.")
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        p.update("Saving", entry.path, done, entry.size)
                    }
                }
            }
            val expected = entry.integrityHash()
            val details = when {
                expected == null -> emptyList()
                Hex.of(md.digest()).equals(expected, ignoreCase = true) -> listOf("SHA-256 matches the archive's integrity hash.")
                else -> listOf("⚠ SHA-256 doesn't match the hash stored in the archive.")
            }
            "Saved ${entry.name} (${formatBytes(done)})." to details
        }
    }

    /** Packs [pendingPackTree] into the newly created document [target]. */
    fun packTo(target: Uri) {
        val tree = pendingPackTree ?: return
        pendingPackTree = null
        val opts = AsarPacker.Options().apply {
            integrity = packIntegrity
            skipHidden = packSkipHidden
        }
        val outName = Saf.displayName(ctx, target)
        runJob("Packing $outName", onFailure = { Saf.deleteQuietly(ctx, target) }) { p ->
            val skip = setOfNotNull(Saf.documentIdOrNull(target))
            val root = SafNode.root(ctx, tree, skip)
            val raw = ctx.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Couldn't write the archive.")
            val r = BufferedOutputStream(raw, 1024 * 1024).use { out -> AsarPacker.pack(root, out, opts, p) }
            val details = ArrayList<String>()
            details += if (opts.integrity) "Integrity hashes included (SHA-256, 4 MiB blocks)." else "No integrity hashes."
            if (opts.skipHidden) details += "Hidden files and folders were left out."
            "Packed ${r.files} files and ${r.folders} folders from \"${root.name()}\" into $outName (${formatBytes(r.archiveSize)})." to details
        }
    }

    override fun onCleared() {
        closeArchive()
        super.onCleared()
    }

    companion object {
        private const val TEXT_LIMIT = 256 * 1024
        private const val HEX_LIMIT = 8 * 1024
        private const val MAX_DETAILS = 60
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")
    }
}

// ---------------------------------------------------------------------- helpers

fun Throwable.friendly(): String = when (this) {
    is AsarException -> message ?: "Something went wrong."
    is SecurityException -> "Permission denied by the storage provider."
    else -> message ?: javaClass.simpleName
}

fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

private fun looksLikeText(b: ByteArray): Boolean {
    if (b.isEmpty()) return true
    var control = 0
    for (x in b) {
        val c = x.toInt() and 0xff
        if (c == 0) return false
        if (c < 9 || (c in 14..31)) control++
    }
    return control * 50 < b.size
}

private fun hexDump(b: ByteArray): List<String> {
    val lines = ArrayList<String>()
    var i = 0
    while (i < b.size) {
        val sb = StringBuilder()
        sb.append("%08x  ".format(i))
        val ascii = StringBuilder()
        for (j in 0 until 16) {
            if (i + j < b.size) {
                val c = b[i + j].toInt() and 0xff
                sb.append("%02x ".format(c))
                ascii.append(if (c in 32..126) c.toChar() else '·')
            } else {
                sb.append("   ")
            }
            if (j == 7) sb.append(' ')
        }
        sb.append(' ').append(ascii)
        lines += sb.toString()
        i += 16
    }
    return lines
}
