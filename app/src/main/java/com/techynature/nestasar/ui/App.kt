package com.techynature.nestasar.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.techynature.nestasar.MainViewModel
import com.techynature.nestasar.Saf
import com.techynature.nestasar.asar.AsarEntry

/** Root composable: wires the system file pickers to the view model and picks a screen. */
@Composable
fun App(vm: MainViewModel) {
    val context = LocalContext.current

    // ---- pickers -------------------------------------------------------
    val pickExtractFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) vm.extractTo(tree) else vm.pendingExtract = null
    }
    val openForBrowse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val openForExtract = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri) {
            vm.pendingExtract = null
            pickExtractFolder.launch(null)
        }
    }
    val createArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Saf.BINARY)) { uri ->
        if (uri != null) vm.packTo(uri) else vm.pendingPackTree = null
    }
    val pickPackFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            vm.pendingPackTree = tree
            val name = Saf.docName(context, Saf.treeRoot(tree))
            createArchive.launch(if (name.endsWith(".asar")) name else "$name.asar")
        }
    }
    val saveSingle = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Saf.BINARY)) { uri ->
        val e = vm.pendingSave
        vm.pendingSave = null
        if (uri != null && e != null) vm.saveFile(e, uri)
    }

    val extractEntry: (AsarEntry) -> Unit = { entry ->
        if (entry.isFile) {
            vm.pendingSave = entry
            saveSingle.launch(entry.name)
        } else {
            vm.pendingExtract = entry
            pickExtractFolder.launch(null)
        }
    }

    // ---- toasts --------------------------------------------------------
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.toast = null
        }
    }

    // ---- screens -------------------------------------------------------
    val archive = vm.archive
    if (archive == null) {
        HomeScreen(
            vm = vm,
            onBrowse = { openForBrowse.launch(arrayOf("*/*")) },
            onExtract = { openForExtract.launch(arrayOf("*/*")) },
            onPack = { pickPackFolder.launch(null) },
        )
    } else {
        BackHandler {
            when {
                vm.preview != null -> vm.preview = null
                vm.query.isNotEmpty() -> vm.query = ""
                vm.goUp() -> Unit
                else -> vm.closeArchive()
            }
        }
        ViewerScreen(
            vm = vm,
            archive = archive,
            onExtractAll = {
                vm.pendingExtract = null
                pickExtractFolder.launch(null)
            },
            onExtractEntry = extractEntry,
        )
    }

    vm.preview?.let { p ->
        PreviewDialog(
            preview = p,
            archiveName = vm.archiveName,
            onClose = { vm.preview = null },
            onSave = { extractEntry(p.entry) },
        )
    }
    if (vm.showInfo && archive != null) InfoDialog(vm, archive) { vm.showInfo = false }
    vm.busy?.let { BusyDialog(it) }
    vm.job?.let { JobDialog(it, onCancel = vm::cancelJob, onDismiss = vm::dismissJob) }
}
