@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.vortex.explorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vortex.explorer.BuildConfig
import com.vortex.explorer.MainViewModel
import com.vortex.explorer.core.ShuffleMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val prefs = vm.prefs
    var confirmForget by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            SectionLabel("Default shuffle")
            ShuffleMode.values().forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.updatePrefs { it.copy(mode = mode) } }.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = prefs.mode == mode, onClick = { vm.updatePrefs { it.copy(mode = mode) } })
                    Column {
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        Text(mode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("How strongly “Lean newer” and “Rediscover” lean: ${prefs.bias.roundToInt()}×", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = prefs.bias,
                    onValueChange = { value -> vm.updatePrefs { it.copy(bias = value) } },
                    valueRange = 1f..12f,
                )
                Text("Include", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KIND_CHOICES.forEach { (kind, label) ->
                        FilterChip(
                            selected = kind in prefs.kinds,
                            onClick = {
                                vm.updatePrefs {
                                    val kinds = if (kind in it.kinds) it.kinds - kind else it.kinds + kind
                                    it.copy(kinds = kinds.ifEmpty { it.kinds })
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                SwitchRow("Include subfolders", prefs.includeSubfolders) { value -> vm.updatePrefs { it.copy(includeSubfolders = value) } }
                SwitchRow("Never the same folder twice in a row", prefs.noSameFolderTwice) { value ->
                    vm.updatePrefs { it.copy(noSameFolderTwice = value) }
                }
            }

            SectionLabel("Slideshow")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Each file stays ${prefs.slideSeconds} seconds", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = prefs.slideSeconds.toFloat(),
                    onValueChange = { value -> vm.updatePrefs { it.copy(slideSeconds = value.roundToInt()) } },
                    valueRange = 2f..30f,
                    steps = 27,
                )
                SwitchRow("At the end, reshuffle and keep going", prefs.loopSlideshow) { value ->
                    vm.updatePrefs { it.copy(loopSlideshow = value) }
                }
            }

            SectionLabel("What you've seen")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow("Remember what I view (powers “Unseen first”)", prefs.markSeen) { value ->
                    vm.updatePrefs { it.copy(markSeen = value) }
                }
                OutlinedButton(onClick = { confirmForget = true }, enabled = vm.seenCount > 0) {
                    Text("Forget everything I've seen (${vm.seenCount})")
                }
                Text(
                    "Stored on this phone as anonymous fingerprints, not file names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionLabel("Browsing")
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow("Show hidden files", prefs.showHidden) { value -> vm.updatePrefs { it.copy(showHidden = value) } }
            }

            SectionLabel("About")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("VortexPlorer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Every shuffle has a seed. The same seed, mode and files give the same order on any " +
                        "phone — share it, or enter it later to get back an order you liked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget what you've seen?",
            text = "“Unseen first” will treat every file as new again.",
            confirm = "Forget",
            onDismiss = { confirmForget = false },
        ) { vm.forgetSeen() }
    }
}
