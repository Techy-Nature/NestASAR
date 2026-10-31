package com.techynature.nestasar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.techynature.nestasar.MainViewModel
import com.techynature.nestasar.R
import com.techynature.nestasar.asar.AsarArchive
import com.techynature.nestasar.asar.AsarEntry
import com.techynature.nestasar.formatBytes

private const val SEARCH_LIMIT = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    vm: MainViewModel,
    archive: AsarArchive,
    onExtractAll: () -> Unit,
    onExtractEntry: (AsarEntry) -> Unit,
) {
    val dir = vm.currentDir ?: archive.root()
    val query = vm.query.trim()

    val allEntries = remember(archive) { archive.walk(archive.root()) }
    val rows: List<AsarEntry> = remember(dir, query, allEntries) {
        if (query.isEmpty()) dir.sortedChildren()
        else allEntries.filter { it.name.contains(query, ignoreCase = true) }.take(SEARCH_LIMIT)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(dir, query) { listState.scrollToItem(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = {
                    IconButton(onClick = { if (!vm.goUp()) vm.closeArchive() }) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(vm.archiveName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${archive.root().fileCount()} files · ${formatBytes(archive.archiveSize())}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.showInfo = true }) {
                        Icon(painterResource(R.drawable.ic_info), contentDescription = "Archive info")
                    }
                    IconButton(onClick = onExtractAll) {
                        Icon(painterResource(R.drawable.ic_extract), contentDescription = "Extract everything", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                placeholder = { Text("Search every file name") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (query.isEmpty()) {
                Breadcrumbs(dir) { vm.currentDir = it }
            } else {
                Text(
                    if (rows.size >= SEARCH_LIMIT) "Showing the first $SEARCH_LIMIT matches" else "${rows.size} match(es)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (rows.isEmpty()) {
                Text(
                    if (query.isEmpty()) "This folder is empty." else "Nothing matches \"$query\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.path }) { e ->
                    EntryRow(
                        entry = e,
                        showPath = query.isNotEmpty(),
                        onClick = {
                            if (e.isDirectory) {
                                vm.query = ""
                                vm.currentDir = e
                            } else {
                                vm.openPreview(e)
                            }
                        },
                        onExtract = { onExtractEntry(e) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(dir: AsarEntry, onPick: (AsarEntry) -> Unit) {
    val chain = remember(dir) {
        val list = ArrayList<AsarEntry>()
        var cur: AsarEntry? = dir
        while (cur != null) {
            list.add(0, cur)
            cur = cur.parent
        }
        list
    }
    val scroll = rememberScrollState()
    LaunchedEffect(dir) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        chain.forEachIndexed { i, e ->
            if (i > 0) Text("›", color = MaterialTheme.colorScheme.outline)
            val last = i == chain.size - 1
            Text(
                if (e.parent == null) "root" else e.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !last) { onPick(e) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EntryRow(entry: AsarEntry, showPath: Boolean, onClick: () -> Unit, onExtract: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when {
        entry.isDirectory -> R.drawable.ic_folder to scheme.secondary
        entry.isLink -> R.drawable.ic_link to scheme.tertiary
        else -> R.drawable.ic_file to scheme.onSurfaceVariant
    }
    val detail = buildString {
        when {
            entry.isDirectory -> {
                val n = entry.children().size
                append(if (n == 1) "1 item" else "$n items")
                append(" · ").append(formatBytes(entry.totalSize()))
            }
            entry.isLink -> append("link → ").append(entry.link)
            else -> append(formatBytes(entry.size))
        }
        if (showPath && entry.parent?.parent != null) append(" · in ").append(entry.parent.path)
    }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = tint) },
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.unpacked) {
                    Badge("unpacked", scheme.secondary)
                    Spacer(Modifier.width(6.dp))
                }
                if (entry.executable) {
                    Badge("exec", scheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            if (!entry.isLink && !entry.unpacked) {
                IconButton(onClick = onExtract) {
                    Icon(
                        painterResource(R.drawable.ic_save),
                        contentDescription = if (entry.isDirectory) "Extract folder" else "Save file",
                        tint = scheme.primary,
                    )
                }
            }
        },
    )
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun FileGlyph(dir: Boolean) {
    Icon(
        painterResource(if (dir) R.drawable.ic_folder else R.drawable.ic_file),
        contentDescription = null,
        tint = if (dir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
