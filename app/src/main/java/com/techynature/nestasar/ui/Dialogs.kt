package com.techynature.nestasar.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.techynature.nestasar.JobState
import com.techynature.nestasar.MainViewModel
import com.techynature.nestasar.Preview
import com.techynature.nestasar.PreviewBody
import com.techynature.nestasar.R
import com.techynature.nestasar.asar.AsarArchive
import com.techynature.nestasar.formatBytes

// ------------------------------------------------------------------ file preview

@Composable
fun PreviewDialog(preview: Preview, archiveName: String, onClose: () -> Unit, onSave: () -> Unit) {
    val e = preview.entry
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "/" + e.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (e.isFile && !e.unpacked) {
                        IconButton(onClick = onSave) {
                            Icon(painterResource(R.drawable.ic_save), contentDescription = "Save file", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    if (e.isFile) Badge(formatBytes(e.size), MaterialTheme.colorScheme.primary)
                    if (e.integrityHash() != null) Badge("SHA-256", MaterialTheme.colorScheme.tertiary)
                    if (e.unpacked) Badge("unpacked", MaterialTheme.colorScheme.secondary)
                    if (e.executable) Badge("exec", MaterialTheme.colorScheme.tertiary)
                    if (e.isFile && !e.unpacked) {
                        Text(
                            "offset ${e.offset}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                when (val body = preview.body) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is PreviewBody.Message -> Text(
                        body.text,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                    is PreviewBody.Image -> Column(Modifier.fillMaxSize()) {
                        Text(body.note, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(14.dp))
                        Image(
                            bitmap = body.bitmap.asImageBitmap(),
                            contentDescription = e.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        )
                    }
                    is PreviewBody.Lines -> Column(Modifier.fillMaxSize()) {
                        body.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        SelectionContainer {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(body.lines) { i, line ->
                                    Row(Modifier.padding(horizontal = 8.dp)) {
                                        Text(
                                            (i + 1).toString().padStart(5),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            line,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ archive info

@Composable
fun InfoDialog(vm: MainViewModel, archive: AsarArchive, onClose: () -> Unit) {
    val all = archive.walk(archive.root())
    val unpacked = all.count { it.isFile && it.unpacked }
    val links = all.count { it.isLink }
    val hashed = all.count { it.isFile && it.integrityHash() != null }
    val files = all.count { it.isFile }
    val folders = all.count { it.isDirectory }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(vm.archiveName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoLine("Archive size", formatBytes(archive.archiveSize()))
                InfoLine("Header (JSON)", formatBytes(archive.headerJsonLength().toLong()))
                InfoLine("Data starts at", "byte ${archive.dataOffset()}")
                InfoLine("Files", files.toString())
                InfoLine("Folders", folders.toString())
                InfoLine("Content size", formatBytes(archive.root().totalSize()))
                InfoLine("With SHA-256 hashes", if (files == 0) "—" else "$hashed of $files")
                if (unpacked > 0) InfoLine("Unpacked (outside)", unpacked.toString())
                if (links > 0) InfoLine("Symlinks", links.toString())
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ progress

@Composable
fun BusyDialog(text: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(18.dp))
                Text(text)
            }
        }
    }
}

@Composable
fun JobDialog(job: JobState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!job.running) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !job.running, dismissOnClickOutside = false),
        title = { Text(if (job.running) job.title else if (job.failed) "That didn't work" else "Done") },
        confirmButton = {
            if (job.running) TextButton(onClick = onCancel) { Text("Cancel") }
            else Button(onClick = onDismiss) { Text("OK") }
        },
        text = {
            if (job.running) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(job.stage, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        job.path.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.total > 0) {
                        val f = (job.done.toFloat() / job.total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                        Text("${formatBytes(job.done)} of ${formatBytes(job.total)}", style = MaterialTheme.typography.labelMedium)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (job.done > 0) Text(formatBytes(job.done) + " read", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(job.summary, color = if (job.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (job.details.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer {
                            Column {
                                job.details.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
