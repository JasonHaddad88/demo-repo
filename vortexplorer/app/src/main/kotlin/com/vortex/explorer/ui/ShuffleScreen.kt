@file:OptIn(ExperimentalMaterial3Api::class)

package com.vortex.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.PickRequest
import com.vortex.explorer.Screen
import com.vortex.explorer.core.ShuffleMode

private enum class ShuffleDialogs { OPTIONS, EXPORT }

@Composable
fun ShuffleScreen(vm: MainViewModel) {
    val session = vm.session
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ShuffleDialogs?>(null) }
    var showProblems by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = {
                    Column {
                        Text(session?.label ?: "Shuffle", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (session != null) {
                            Text(
                                "${session.result.total} files · ${session.result.mode.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(onClick = { vm.reshuffle() }) { Icon(VortexIcons.Shuffle, "Shuffle again") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Change order or seed…") },
                                    leadingIcon = { Icon(VortexIcons.Shuffle, null) },
                                    onClick = { menu = false; dialog = ShuffleDialogs.OPTIONS },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export as numbered files…") },
                                    leadingIcon = { Icon(VortexIcons.Numbered, null) },
                                    onClick = { menu = false; dialog = ShuffleDialogs.EXPORT },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy all to…") },
                                    leadingIcon = { Icon(VortexIcons.Copy, null) },
                                    onClick = {
                                        menu = false
                                        vm.go(Screen.Pick(PickRequest.Copy(session.result.items, move = false)))
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { EmptyState("Nothing shuffled yet") }
            return@Scaffold
        }
        val items = session.result.items
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.go(Screen.Viewer(items, 0, autoplay = true, fromShuffle = true)) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play slideshow")
                        }
                        OutlinedButton(
                            onClick = {
                                vm.pickOne()?.let { vm.go(Screen.Viewer(listOf(it), 0, autoplay = false, fromShuffle = false)) }
                            },
                        ) {
                            Icon(VortexIcons.Dice, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pick one")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                clipboard.setText(AnnotatedString(session.result.seed))
                                vm.say("Seed ${session.result.seed} copied — enter it later to get this exact order")
                            },
                            label = { Text("Seed ${session.result.seed}") },
                        )
                        if (session.result.mode == ShuffleMode.UNSEEN_FIRST) {
                            Text(
                                "${session.result.unseenCount} not seen yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (session.truncated) {
                        Notice("Stopped gathering at ${session.pool.size} files. Shuffle a smaller folder to include everything.")
                    }
                    if (session.problems.isNotEmpty()) {
                        Notice(
                            "${session.problems.size} ${if (session.problems.size == 1) "folder" else "folders"} " +
                                "could not be read" + if (showProblems) ":\n" + session.problems.take(20).joinToString("\n") else " — tap for details",
                            onClick = { showProblems = !showProblems },
                        )
                    }
                }
            }
            itemsIndexed(items, key = { _, entry -> entry.key }) { index, entry ->
                EntryTile(
                    entry = entry,
                    images = vm.images,
                    badge = (index + 1).toString(),
                    onClick = { vm.go(Screen.Viewer(items, index, autoplay = false, fromShuffle = true)) },
                )
            }
        }
    }

    if (session != null) {
        when (dialog) {
            ShuffleDialogs.OPTIONS -> ShuffleDialog(
                title = "Reshuffle these ${session.result.total} files",
                prefs = vm.prefs,
                showScope = false,
                initialMode = session.options.mode,
                onDismiss = { dialog = null },
            ) { kinds, recursive, options, makeDefault ->
                if (makeDefault) vm.saveDefaults(kinds, recursive, options)
                vm.reshuffle(options)
            }
            ShuffleDialogs.EXPORT -> ExportDialog(
                count = session.result.items.size,
                defaultFolder = "Shuffle ${session.result.seed}",
                onDismiss = { dialog = null },
            ) { options, newFolder ->
                vm.go(Screen.Pick(PickRequest.Export(session.result.items, options, newFolder)))
            }
            null -> Unit
        }
    }
}

@Composable
private fun Notice(text: String, onClick: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
