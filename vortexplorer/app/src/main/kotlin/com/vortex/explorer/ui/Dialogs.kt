@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vortex.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.core.ExportOptions
import com.vortex.explorer.core.ShuffleMode
import com.vortex.explorer.core.ShuffleOptions
import com.vortex.explorer.core.SmbSource
import com.vortex.explorer.data.Preferences
import com.vortex.explorer.data.SourceSpec
import kotlinx.coroutines.launch

/** Add or edit an SMB share or a WebDAV server, with a "Test" that really connects. */
@Composable
fun NetworkSourceDialog(
    vm: MainViewModel,
    kind: String,
    existing: SourceSpec?,
    onDismiss: () -> Unit,
) {
    val smb = existing as? SourceSpec.Smb
    val dav = existing as? SourceSpec.WebDav
    val isSmb = kind == "smb"
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(smb?.host.orEmpty()) }
    var port by remember { mutableStateOf((smb?.port ?: SmbSource.DEFAULT_PORT).toString()) }
    var share by remember { mutableStateOf(smb?.share.orEmpty()) }
    var path by remember { mutableStateOf(smb?.path?.takeIf { it != "/" }.orEmpty()) }
    var domain by remember { mutableStateOf(smb?.domain.orEmpty()) }
    var url by remember { mutableStateOf(dav?.url.orEmpty()) }
    var user by remember { mutableStateOf(smb?.user ?: dav?.user.orEmpty()) }
    var password by remember { mutableStateOf(existing?.let { vm.storedPassword(it.id) }.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun build(): SourceSpec? {
        val id = existing?.id ?: SourceSpec.newId()
        return if (isSmb) {
            val cleanHost = host.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/', '\\')
            if (cleanHost.isEmpty() || share.isBlank()) return null
            SourceSpec.Smb(
                id = id,
                name = name.ifBlank { "$cleanHost/${share.trim()}" },
                host = cleanHost,
                port = port.toIntOrNull() ?: SmbSource.DEFAULT_PORT,
                share = share.trim().trim('/', '\\'),
                path = "/" + path.trim().trim('/', '\\').replace('\\', '/'),
                user = user.trim(),
                domain = domain.trim(),
            )
        } else {
            if (url.isBlank()) return null
            SourceSpec.WebDav(id = id, name = name.ifBlank { url.trim().substringAfter("://").substringBefore('/') }, url = url.trim(), user = user.trim())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSmb) "Windows / SMB share" else "WebDAV server") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSmb) {
                    Text(
                        "A shared folder on a PC or NAS. On Windows: right-click a folder → Properties → Sharing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field("Computer name or IP", host, { host = it }, placeholder = "192.168.1.20")
                    Field("Share name", share, { share = it }, placeholder = "Photos")
                    Field("Folder inside the share (optional)", path, { path = it }, placeholder = "2024/Holiday")
                } else {
                    Text(
                        "Nextcloud, ownCloud, most NAS boxes, or anything serving WebDAV.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Field("Address", url, { url = it }, placeholder = "https://cloud.example.com/remote.php/dav/files/me/", keyboard = KeyboardType.Uri)
                }
                Field("User name (empty for guest)", user, { user = it })
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isSmb) {
                    Field("Domain (usually empty)", domain, { domain = it })
                    Field("Port", port, { port = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
                }
                Field("Name in VortexPlorer (optional)", name, { name = it })
                if (!isSmb && url.trim().startsWith("http://")) {
                    Text(
                        "http:// sends your password unencrypted — fine at home, not on public Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val spec = build()
                    if (spec == null) {
                        status = "Fill in the address first"
                    } else {
                        vm.saveNetworkSource(spec, password)
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !testing,
                    onClick = {
                        val spec = build()
                        if (spec == null) {
                            status = "Fill in the address first"
                        } else {
                            testing = true
                            status = "Connecting…"
                            scope.launch {
                                val problem = vm.testSource(spec, password)
                                status = problem ?: "Connected — this works"
                                testing = false
                            }
                        }
                    },
                ) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Everything about a shuffle in one place. With [showScope] off (reshuffling
 * files already gathered) only the order settings are offered.
 */
@Composable
fun ShuffleDialog(
    title: String,
    prefs: Preferences,
    showScope: Boolean,
    initialMode: ShuffleMode = prefs.mode,
    initialSeed: String = "",
    onDismiss: () -> Unit,
    onShuffle: (kinds: Set<EntryKind>, recursive: Boolean, options: ShuffleOptions, remember: Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var kinds by remember { mutableStateOf(prefs.kinds) }
    var recursive by remember { mutableStateOf(prefs.includeSubfolders) }
    var noRepeat by remember { mutableStateOf(prefs.noSameFolderTwice) }
    var bias by remember { mutableFloatStateOf(prefs.bias) }
    var seed by remember { mutableStateOf(initialSeed) }
    var makeDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (showScope) {
                    Text("What to include", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KIND_CHOICES.forEach { (kind, label) ->
                            FilterChip(
                                selected = kind in kinds,
                                onClick = { kinds = if (kind in kinds) kinds - kind else kinds + kind },
                                label = { Text(label) },
                            )
                        }
                    }
                    SwitchRow("Include subfolders", recursive) { recursive = it }
                }
                Text("Order", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ShuffleMode.values().forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { mode = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == option, onClick = { mode = option })
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(option.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (mode == ShuffleMode.NEWER_FIRST || mode == ShuffleMode.OLDER_FIRST) {
                    Text("How strongly: ${"%.0f".format(bias)}×", style = MaterialTheme.typography.bodySmall)
                    Slider(value = bias, onValueChange = { bias = it }, valueRange = 1f..12f)
                }
                if (mode != ShuffleMode.FOLDER_SPREAD) {
                    SwitchRow("Never the same folder twice in a row", noRepeat) { noRepeat = it }
                }
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text("Seed (optional)") },
                    supportingText = { Text("Empty for a fresh shuffle. Same seed, same files, same mode → same order.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                SwitchRow("Make these my defaults", makeDefault) { makeDefault = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kinds.isNotEmpty() || !showScope,
                onClick = {
                    val options = ShuffleOptions(
                        mode = mode,
                        seed = seed.trim().ifEmpty { null },
                        noSameFolderTwice = noRepeat,
                        bias = bias.toDouble(),
                    )
                    onShuffle(kinds, recursive, options, makeDefault)
                    onDismiss()
                },
            ) { Text("Shuffle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

val KIND_CHOICES = listOf(
    EntryKind.IMAGE to "Photos",
    EntryKind.VIDEO to "Videos",
    EntryKind.AUDIO to "Music",
    EntryKind.OTHER to "Other files",
)

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Options for writing a shuffle out as numbered files. */
@Composable
fun ExportDialog(
    count: Int,
    defaultFolder: String,
    onDismiss: () -> Unit,
    onExport: (ExportOptions, newFolder: String?) -> Unit,
) {
    var keepNames by remember { mutableStateOf(true) }
    var playlist by remember { mutableStateOf(true) }
    var inNewFolder by remember { mutableStateOf(true) }
    var folder by remember { mutableStateOf(defaultFolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export $count files in this order") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Copies the files with numbers in front — 001, 002, 003… — so car stereos, TVs and " +
                        "photo frames that play in name order play your shuffle. Pick the destination next: " +
                        "a USB stick, a folder, or a network share.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow("Keep the original names after the number", keepNames) { keepNames = it }
                SwitchRow("Add a playlist (.m3u) for music and video", playlist) { playlist = it }
                SwitchRow("Put them in a new folder", inNewFolder) { inNewFolder = it }
                if (inNewFolder) {
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onExport(
                        ExportOptions(keepNames = keepNames, playlist = playlist),
                        if (inNewFolder) folder.trim().ifEmpty { defaultFolder } else null,
                    )
                    onDismiss()
                },
            ) { Text("Choose destination") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(text.trim())
                    onDismiss()
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
