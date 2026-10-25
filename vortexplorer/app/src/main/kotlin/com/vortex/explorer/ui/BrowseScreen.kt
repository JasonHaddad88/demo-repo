@file:OptIn(ExperimentalMaterial3Api::class)

package com.vortex.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.PickRequest
import com.vortex.explorer.Screen
import com.vortex.explorer.core.Capability
import com.vortex.explorer.core.Entry
import com.vortex.explorer.data.SourceSpec
import kotlinx.coroutines.CancellationException

private sealed class BrowseDialog {
    data object ShuffleFolder : BrowseDialog()
    data object ShuffleSelection : BrowseDialog()
    data object NewFolder : BrowseDialog()
    data class Rename(val entry: Entry) : BrowseDialog()
    data object Delete : BrowseDialog()
    data object Renumber : BrowseDialog()
}

/**
 * One folder of one source. With [pick] set it becomes a destination picker:
 * folders only, and a button to copy, move or export into the folder shown.
 */
@Composable
fun BrowseScreen(
    vm: MainViewModel,
    sourceId: String,
    path: String,
    title: String,
    pick: PickRequest?,
) {
    val source = vm.source(sourceId)
    var entries by remember(sourceId, path) { mutableStateOf<List<Entry>?>(null) }
    var error by remember(sourceId, path) { mutableStateOf<String?>(null) }
    var selected by remember(sourceId, path) { mutableStateOf(setOf<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BrowseDialog?>(null) }

    val writable = source?.can(Capability.WRITE) == true
    val canDelete = source?.can(Capability.DELETE) == true
    val canRename = source?.can(Capability.RENAME) == true

    LaunchedEffect(sourceId, path, vm.revision, reload) {
        error = null
        try {
            entries = vm.list(sourceId, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "could not open this folder"
            if (entries == null) entries = emptyList()
        }
    }

    val all = entries.orEmpty()
    val visible = if (pick != null) all.filter { it.isFolder } else all
    val chosen = visible.filter { it.key in selected }
    val selecting = chosen.isNotEmpty() && pick == null

    fun toggle(entry: Entry) {
        selected = if (entry.key in selected) selected - entry.key else selected + entry.key
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                    },
                    title = { Text("${chosen.size} selected") },
                    actions = {
                        IconButton(onClick = { dialog = BrowseDialog.ShuffleSelection }) {
                            Icon(VortexIcons.Shuffle, "Shuffle the selection")
                        }
                        IconButton(onClick = { vm.go(Screen.Pick(PickRequest.Copy(chosen, move = false))) }) {
                            Icon(VortexIcons.Copy, "Copy to…")
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (canDelete) {
                                    MenuItem("Move to…") { menu = false; vm.go(Screen.Pick(PickRequest.Copy(chosen, move = true))) }
                                }
                                if (canRename && chosen.size == 1) {
                                    MenuItem("Rename") { menu = false; dialog = BrowseDialog.Rename(chosen.single()) }
                                }
                                if (canDelete) {
                                    MenuItem("Delete") { menu = false; dialog = BrowseDialog.Delete }
                                }
                                MenuItem("Select all") { menu = false; selected = visible.map { it.key }.toSet() }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        Column {
                            Text(
                                if (pick != null) "Choose where — $title" else title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                source?.describe(path).orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (pick == null) {
                            IconButton(onClick = { dialog = BrowseDialog.ShuffleFolder }) {
                                Icon(VortexIcons.Shuffle, "Shuffle this folder")
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (writable) {
                                        MenuItem("New folder") { menu = false; dialog = BrowseDialog.NewFolder }
                                    }
                                    if (canRename) {
                                        MenuItem("Renumber files in random order") { menu = false; dialog = BrowseDialog.Renumber }
                                    }
                                    MenuItem("Refresh") { menu = false; reload++ }
                                }
                            }
                        } else if (writable) {
                            IconButton(onClick = { dialog = BrowseDialog.NewFolder }) { Icon(Icons.Filled.Add, "New folder") }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (pick != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { vm.cancelPick() }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = writable,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                when (pick) {
                                    is PickRequest.Copy -> vm.copyHere(pick, sourceId, path)
                                    is PickRequest.Export -> vm.exportHere(pick, sourceId, path)
                                }
                            },
                        ) { Text(if (writable) pick.verb else "This place is read-only") }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val problem = error
            when {
                entries == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                problem != null && all.isEmpty() -> EmptyState("Couldn't open this folder", problem) {
                    Button(onClick = { reload++ }) { Text("Try again") }
                }
                visible.isEmpty() -> EmptyState(
                    if (pick != null) "No folders in here" else "This folder is empty",
                    if (pick != null) "You can still ${pick.verb.lowercase()}, or make a new folder with +." else "",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.key }) { entry ->
                        EntryTile(
                            entry = entry,
                            images = vm.images,
                            selected = entry.key in selected,
                            onClick = {
                                when {
                                    selecting -> toggle(entry)
                                    entry.isFolder && pick != null ->
                                        vm.go(Screen.Pick(pick, sourceId, entry.path, entry.name))
                                    entry.isFolder -> vm.go(Screen.Browse(sourceId, entry.path, entry.name))
                                    else -> {
                                        val files = visible.filter { !it.isFolder }
                                        vm.go(Screen.Viewer(files, files.indexOf(entry), autoplay = false, fromShuffle = false))
                                    }
                                }
                            },
                            onLongClick = if (pick == null) ({ toggle(entry) }) else null,
                        )
                    }
                }
            }
        }
    }

    when (val open = dialog) {
        null -> Unit
        BrowseDialog.ShuffleFolder -> ShuffleDialog(
            title = "Shuffle $title",
            prefs = vm.prefs,
            showScope = true,
            onDismiss = { dialog = null },
        ) { kinds, recursive, options, makeDefault ->
            if (makeDefault) vm.saveDefaults(kinds, recursive, options)
            vm.shuffle(title, listOf(sourceId to path), kinds, recursive, options)
        }
        BrowseDialog.ShuffleSelection -> ShuffleDialog(
            title = "Shuffle ${chosen.size} selected",
            prefs = vm.prefs,
            showScope = true,
            onDismiss = { dialog = null },
        ) { kinds, recursive, options, makeDefault ->
            if (makeDefault) vm.saveDefaults(kinds, recursive, options)
            vm.shuffle(
                label = "$title (selection)",
                origins = chosen.filter { it.isFolder }.map { sourceId to it.path },
                kinds = kinds,
                recursive = recursive,
                options = options,
                extra = chosen.filter { !it.isFolder },
            )
            selected = emptySet()
        }
        BrowseDialog.NewFolder -> TextPromptDialog(
            title = "New folder",
            label = "Name",
            confirm = "Create",
            onDismiss = { dialog = null },
        ) { name -> vm.mkdir(sourceId, path, name) }
        is BrowseDialog.Rename -> TextPromptDialog(
            title = "Rename",
            label = "New name",
            initial = open.entry.name,
            confirm = "Rename",
            onDismiss = { dialog = null },
        ) { name ->
            vm.rename(open.entry, name)
            selected = emptySet()
        }
        BrowseDialog.Delete -> ConfirmDialog(
            title = "Delete ${chosen.size} ${if (chosen.size == 1) "item" else "items"}?",
            text = "They are deleted from ${source?.displayName ?: "this place"}, folders with everything in them. This cannot be undone.",
            confirm = "Delete",
            onDismiss = { dialog = null },
        ) {
            vm.delete(chosen)
            selected = emptySet()
        }
        BrowseDialog.Renumber -> ConfirmDialog(
            title = "Renumber files in random order?",
            text = "Every file directly in this folder gets a number in front of its name — 001, 002, 003… — " +
                "in a fresh random order, so car stereos and TVs that play by name play them shuffled. " +
                "Run it again any time for a new order: old numbers are replaced, not stacked.",
            confirm = "Renumber",
            onDismiss = { dialog = null },
        ) { vm.reshuffleInPlace(sourceId, path) }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}

/** First step of picking a destination: which place. */
@Composable
fun PickRootScreen(vm: MainViewModel, request: PickRequest) {
    val places = vm.sources.filter { it !is SourceSpec.Gallery }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text(request.verb.removeSuffix(" here") + " to…") },
            )
        },
    ) { padding ->
        if (places.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    "Nowhere to put them yet",
                    "Add a folder, a USB drive or a network share on the home screen first. " +
                        "The photo gallery is read-only.",
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        when (request) {
                            is PickRequest.Copy -> "${request.items.size} ${if (request.items.size == 1) "item" else "items"}"
                            is PickRequest.Export -> "${request.order.size} files, numbered in shuffled order"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(places, key = { it.id }) { spec ->
                    ListItem(
                        modifier = Modifier.clickable {
                            vm.source(spec.id)?.let { vm.go(Screen.Pick(request, spec.id, it.rootPath, spec.name)) }
                        },
                        leadingContent = {
                            Icon(
                                if (spec.isNetwork) VortexIcons.Server else VortexIcons.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text(spec.name) },
                    )
                }
            }
        }
    }
}
