@file:OptIn(ExperimentalFoundationApi::class)

package com.vortex.explorer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.explorer.Busy
import com.vortex.explorer.core.Entry
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.media.ImageLoader

const val THUMB_PX = 320

fun iconFor(entry: Entry): ImageVector = when (entry.kind) {
    EntryKind.FOLDER -> VortexIcons.Folder
    EntryKind.IMAGE -> VortexIcons.Photo
    EntryKind.VIDEO -> VortexIcons.Video
    EntryKind.AUDIO -> VortexIcons.Music
    EntryKind.OTHER -> VortexIcons.Document
}

/** A square preview: the picture when there is one, an icon otherwise. */
@Composable
fun Thumbnail(entry: Entry, images: ImageLoader, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(images.cached(entry, THUMB_PX), entry.key, entry.modified) {
        if (value == null) value = runCatching { images.thumbnail(entry, THUMB_PX) }.getOrNull()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(loaded, contentDescription = entry.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (entry.kind == EntryKind.VIDEO) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                ) {
                    Icon(VortexIcons.Video, null, tint = Color.White, modifier = Modifier.padding(3.dp).size(14.dp))
                }
            }
        } else {
            Icon(
                iconFor(entry),
                contentDescription = null,
                tint = if (entry.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (entry.isFolder) 48.dp else 36.dp),
            )
        }
    }
}

/** One cell of a grid: preview, name, optional position badge and selection tick. */
@Composable
fun EntryTile(
    entry: Entry,
    images: ImageLoader,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    badge: String? = null,
    dimmed: Boolean = false,
) {
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box {
            Thumbnail(entry, images, Modifier.fillMaxWidth().aspectRatio(1f))
            if (dimmed) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)))
            }
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                ) {
                    Text(
                        badge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                )
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Covers the box it sits in; aspectRatio children give the box its size. */
private fun Modifier.matchParentSize(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(1f))

@Composable
fun EmptyState(title: String, detail: String = "", action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(VortexIcons.Shuffle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Progress for anything slow: walking a share, copying, exporting. */
@Composable
fun BusyDialog(busy: Busy, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(busy.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val fraction = busy.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (busy.detail.isNotEmpty()) {
                    Text(busy.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            if (busy.cancellable) TextButton(onClick = onCancel) { Text("Stop") }
        },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
