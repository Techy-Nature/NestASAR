package com.techynature.nestasar.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.techynature.nestasar.MainViewModel
import com.techynature.nestasar.R

@Composable
fun HomeScreen(vm: MainViewModel, onBrowse: () -> Unit, onExtract: () -> Unit, onPack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        Surface(color = Color(0xFF15201B), modifier = Modifier.fillMaxSize()) {
                            Image(
                                painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("NestASAR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Open, extract and pack Electron .asar archives",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                ActionCard(
                    icon = R.drawable.ic_browse,
                    title = "Browse an archive",
                    body = "Step 1: tap here.\nStep 2: pick a .asar file.\nYou can look inside, preview files, and save any file or folder out of it.",
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = onBrowse,
                )

                ActionCard(
                    icon = R.drawable.ic_extract,
                    title = "Extract everything",
                    body = "Step 1: tap here and pick a .asar file.\nStep 2: pick where to put it.\nA new folder named after the archive is made there.",
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = onExtract,
                ) {
                    OptionRow("Check SHA-256 hashes while extracting", vm.verifyOnExtract) { vm.verifyOnExtract = it }
                }

                ActionCard(
                    icon = R.drawable.ic_pack,
                    title = "Pack a folder",
                    body = "Step 1: tap here and pick the folder to pack (its contents become the archive's root).\nStep 2: choose a name and place for the new .asar file.",
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = onPack,
                ) {
                    OptionRow("Add integrity hashes (recommended for Electron 30+)", vm.packIntegrity) { vm.packIntegrity = it }
                    OptionRow("Leave out hidden files (.git, .DS_Store…)", vm.packSkipHidden) { vm.packSkipHidden = it }
                }

                Text(
                    "Tip: files marked \"unpacked\" (often native .node modules) aren't stored inside the .asar. " +
                        "They sit in the app.asar.unpacked folder beside it, so copy that folder along with the archive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    accent: Color,
    onClick: () -> Unit,
    options: (@Composable () -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(color = accent.copy(alpha = 0.16f), modifier = Modifier.fillMaxSize()) {}
                    Icon(painterResource(icon), contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (options != null) {
                Column(Modifier.padding(start = 18.dp, end = 12.dp, bottom = 10.dp)) { options() }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
