@file:OptIn(ExperimentalMaterial3Api::class)

package com.vortex.explorer.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.Screen
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.core.ShuffleOptions
import com.vortex.explorer.data.SourceSpec

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAddFolder: () -> Unit,
    withGallery: (() -> Unit) -> Unit,
) {
    var addMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SourceSpec?>(null) }
    var removing by remember { mutableStateOf<SourceSpec?>(null) }
    var shuffling by remember { mutableStateOf<List<SourceSpec>?>(null) }

    fun needsGallery(specs: List<SourceSpec>, action: () -> Unit) {
        if (specs.any { it is SourceSpec.Gallery }) withGallery(action) else action()
    }

    fun open(spec: SourceSpec) {
        val root = if (spec is SourceSpec.Gallery) "/" else vm.source(spec.id)?.rootPath ?: return
        needsGallery(listOf(spec)) { vm.go(Screen.Browse(spec.id, root, spec.name)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VortexPlorer") },
                actions = {
                    IconButton(onClick = { vm.go(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add a place") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Folder, SD card or USB drive") },
                        leadingIcon = { Icon(VortexIcons.Drive, null) },
                        onClick = {
                            addMenu = false
                            onAddFolder()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Windows / SMB share") },
                        leadingIcon = { Icon(VortexIcons.Server, null) },
                        onClick = {
                            addMenu = false
                            adding = "smb"
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("WebDAV server") },
                        leadingIcon = { Icon(VortexIcons.Server, null) },
                        onClick = {
                            addMenu = false
                            adding = "webdav"
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (vm.mix.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { vm.mix = emptySet() }) { Text("Clear") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { shuffling = vm.sources.filter { it.id in vm.mix } },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(VortexIcons.Shuffle, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.mix.size == 1) "Shuffle it" else "Shuffle ${vm.mix.size} places together")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp)) {
            vm.lastShuffle?.let { last ->
                item(key = "resume") {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Pick up where you left off", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${last.label} · ${last.mode.label} · seed ${last.seed}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("You were on file ${last.position + 1}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val specs = last.origins.mapNotNull { (id, _) -> vm.spec(id) }
                                Button(onClick = { needsGallery(specs) { vm.resumeLast() } }) {
                                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Resume")
                                }
                                OutlinedButton(
                                    onClick = {
                                        needsGallery(specs) {
                                            vm.shuffle(
                                                label = last.label,
                                                origins = last.origins,
                                                kinds = last.kinds,
                                                recursive = last.includeSubfolders,
                                                options = vm.optionsFromPrefs().copy(mode = last.mode),
                                            )
                                        }
                                    },
                                ) { Text("New order") }
                            }
                        }
                    }
                }
            }

            item(key = "places") {
                SectionLabel("Your places")
                Text(
                    "Tap to browse. Tick several to shuffle them together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            items(vm.sources, key = { it.id }) { spec ->
                SourceRow(
                    spec = spec,
                    inMix = spec.id in vm.mix,
                    onOpen = { open(spec) },
                    onMix = { checked -> vm.mix = if (checked) vm.mix + spec.id else vm.mix - spec.id },
                    onShuffle = { shuffling = listOf(spec) },
                    onEdit = { editing = spec },
                    onRemove = { removing = spec },
                )
            }

            if (vm.sources.size == 1) {
                item(key = "hint") {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Add more places", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Plug in a USB drive and choose it with “Folder, SD card or USB drive”, or add a " +
                                    "shared folder from your PC or NAS. Then shuffle them on their own or together.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    shuffling?.let { specs ->
        val title = if (specs.size == 1) "Shuffle ${specs.single().name}" else "Shuffle ${specs.size} places"
        ShuffleDialog(
            title = title,
            prefs = vm.prefs,
            showScope = true,
            onDismiss = { shuffling = null },
        ) { kinds, recursive, options, makeDefault ->
            if (makeDefault) vm.saveDefaults(kinds, recursive, options)
            needsGallery(specs) {
                vm.shuffle(
                    label = specs.joinToString(" + ") { it.name },
                    origins = specs.mapNotNull { spec -> vm.shuffleRoot(spec.id)?.let { spec.id to it } },
                    kinds = kinds,
                    recursive = recursive,
                    options = options,
                )
            }
        }
    }

    adding?.let { kind -> NetworkSourceDialog(vm, kind, existing = null) { adding = null } }
    editing?.let { spec ->
        NetworkSourceDialog(vm, if (spec is SourceSpec.Smb) "smb" else "webdav", existing = spec) { editing = null }
    }
    removing?.let { spec ->
        ConfirmDialog(
            title = "Remove ${spec.name}?",
            text = "VortexPlorer forgets this place and any saved password. Nothing on it is deleted.",
            confirm = "Remove",
            onDismiss = { removing = null },
        ) { vm.removeSource(spec) }
    }
}

@Composable
private fun SourceRow(
    spec: SourceSpec,
    inMix: Boolean,
    onOpen: () -> Unit,
    onMix: (Boolean) -> Unit,
    onShuffle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { Icon(iconForSpec(spec), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(spec.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(describeSpec(spec), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = inMix, onCheckedChange = onMix)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Shuffle all of it") },
                            leadingIcon = { Icon(VortexIcons.Shuffle, null) },
                            onClick = {
                                menu = false
                                onShuffle()
                            },
                        )
                        if (spec.isNetwork) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = {
                                    menu = false
                                    onEdit()
                                },
                            )
                        }
                        if (spec !is SourceSpec.Gallery) {
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = {
                                    menu = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun iconForSpec(spec: SourceSpec): ImageVector = when (spec) {
    is SourceSpec.Gallery -> VortexIcons.Photo
    is SourceSpec.Folder -> if (isRemovable(spec)) VortexIcons.Drive else VortexIcons.Folder
    is SourceSpec.Smb, is SourceSpec.WebDav -> VortexIcons.Server
}

/** Internal storage is "primary:…"; SD cards and USB drives have a volume id instead. */
private fun isRemovable(spec: SourceSpec.Folder): Boolean = runCatching {
    val uri = Uri.parse(spec.treeUri)
    uri.authority == "com.android.externalstorage.documents" &&
        !DocumentsContract.getTreeDocumentId(uri).startsWith("primary:")
}.getOrDefault(false)

private fun describeSpec(spec: SourceSpec): String = when (spec) {
    is SourceSpec.Gallery -> "Every photo and video on this phone"
    is SourceSpec.Folder -> if (isRemovable(spec)) "SD card or USB drive" else "Folder on this phone"
    is SourceSpec.Smb -> buildString {
        append("smb://").append(spec.host)
        if (spec.port != 445) append(':').append(spec.port)
        append('/').append(spec.share)
        if (spec.path != "/") append(spec.path)
    }
    is SourceSpec.WebDav -> spec.url
}

/** "Make these my defaults" from any shuffle dialog. */
fun MainViewModel.saveDefaults(kinds: Set<EntryKind>, recursive: Boolean, options: ShuffleOptions) {
    updatePrefs {
        it.copy(
            mode = options.mode,
            kinds = kinds,
            includeSubfolders = recursive,
            noSameFolderTwice = options.noSameFolderTwice,
            bias = options.bias.toFloat(),
        )
    }
}
