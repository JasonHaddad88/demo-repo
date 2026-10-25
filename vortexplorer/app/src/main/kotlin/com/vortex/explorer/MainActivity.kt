package com.vortex.explorer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vortex.explorer.ui.BrowseScreen
import com.vortex.explorer.ui.BusyDialog
import com.vortex.explorer.ui.HomeScreen
import com.vortex.explorer.ui.PickRootScreen
import com.vortex.explorer.ui.SettingsScreen
import com.vortex.explorer.ui.ShuffleScreen
import com.vortex.explorer.ui.ViewerScreen
import com.vortex.explorer.ui.VortexTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VortexTheme {
                Surface(Modifier.fillMaxSize()) { VortexApp(vm) }
            }
        }
    }
}

@Composable
fun VortexApp(vm: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    // The gallery needs a runtime permission; anything else that needs it waits here.
    var waitingForGallery by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val action = waitingForGallery
        waitingForGallery = null
        if (results.values.any { it }) {
            action?.invoke()
        } else {
            vm.say("Without that permission the gallery stays empty — folders and shares still work")
        }
    }
    val withGallery: (() -> Unit) -> Unit = { action ->
        if (hasGalleryAccess(context)) {
            action()
        } else {
            waitingForGallery = action
            permissions.launch(galleryPermissions())
        }
    }

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    // Keeps each screen's scroll position while you go deeper and come back.
    val saved = rememberSaveableStateHolder()
    val index = vm.backStack.lastIndex
    val screen = vm.screen
    val stateKey = "$index:" + when (screen) {
        is Screen.Browse -> "browse:${screen.sourceId}:${screen.path}"
        is Screen.Pick -> "pick:${screen.sourceId}:${screen.path}"
        else -> screen.javaClass.simpleName
    }

    Box(Modifier.fillMaxSize()) {
        saved.SaveableStateProvider(stateKey) {
            when (screen) {
                Screen.Home -> HomeScreen(vm, onAddFolder = { folderPicker.launch(null) }, withGallery = withGallery)
                is Screen.Browse -> BrowseScreen(vm, screen.sourceId, screen.path, screen.title, pick = null)
                Screen.Shuffle -> ShuffleScreen(vm)
                is Screen.Viewer -> ViewerScreen(vm, screen)
                is Screen.Pick -> {
                    val sourceId = screen.sourceId
                    val path = screen.path
                    if (sourceId == null || path == null) {
                        PickRootScreen(vm, screen.request)
                    } else {
                        BrowseScreen(vm, sourceId, path, screen.title, pick = screen.request)
                    }
                }
                Screen.Settings -> SettingsScreen(vm)
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
        )
        vm.busy?.let { BusyDialog(it, onCancel = vm::cancelJob) }
    }
}

private fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Any of them will do: Android 14's "selected photos only" counts as access. */
private fun hasGalleryAccess(context: Context): Boolean = galleryPermissions().any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
