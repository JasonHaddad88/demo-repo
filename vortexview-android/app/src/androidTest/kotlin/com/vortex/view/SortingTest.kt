package com.vortex.view

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import com.vortex.view.data.Prefs
import com.vortex.view.ui.VortexTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Uses the real app on a real (emulated) phone: opens a folder of three
 * photos in plain colours, then swipes, moves and undoes by touch, checking
 * after each step which photo is actually on screen and where the files are.
 * Saves a screenshot of every step.
 */
@RunWith(AndroidJUnit4::class)
class SortingTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var camera: File
    private lateinit var keepers: File
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        val root = File(context.filesDir, "sorting-test").apply { deleteRecursively() }
        camera = File(root, "Camera").apply { mkdirs() }
        keepers = File(root, "Keepers").apply { mkdirs() }
        photo("IMG_1.jpg", Color.RED, 1600, 1200)
        photo("IMG_2.jpg", Color.GREEN, 1200, 1600)
        photo("IMG_3.jpg", Color.BLUE, 1600, 1200)

        val prefs = Prefs(context, "vortexview-test").apply { clear() }
        vm = MainViewModel(context, FilePlaces(), prefs)
        compose.setContent { VortexTheme { VortexViewApp(vm) } }
    }

    @After
    fun tearDown() {
        vm.loader.close()
    }

    private fun photo(name: String, colour: Int, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(colour)
        File(camera, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    // -- looking at the screen ------------------------------------------------

    private fun screen(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun centre(): Int = screen().let { it.getPixel(it.width / 2, it.height / 2) }

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 70 && Color.blue(c) < 70
    private fun isGreen(c: Int) = Color.green(c) > 200 && Color.red(c) < 70 && Color.blue(c) < 70
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 70 && Color.green(c) < 70

    private fun describe(c: Int) = "rgb(${Color.red(c)}, ${Color.green(c)}, ${Color.blue(c)})"

    /** Waits until the photo in the middle of the screen is [what]. */
    private fun waitForPhoto(what: String, test: (Int) -> Boolean) {
        var last = 0
        repeat(100) {
            compose.waitForIdle()
            last = centre()
            if (test(last)) return
            Thread.sleep(100)
        }
        throw AssertionError("expected the $what photo in the middle of the screen, saw ${describe(last)}")
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(100) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val shot = screen()
        // A screenshot that can't be saved mustn't hide what the test found.
        runCatching {
            TestStorage().openOutputFile("vortexview-$name.png").use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { android.util.Log.w("SortingTest", "couldn't save screenshot $name", it) }
    }

    // -- the test -------------------------------------------------------------

    @Test
    fun openSwipeMoveAndUndo() {
        compose.onNodeWithText("Open a folder of photos").assertExists()
        save("1-welcome")

        vm.openFolder(Uri.fromFile(camera))
        waitFor("the folder to open") { vm.state.photos.size == 3 }
        waitForPhoto("red (IMG_1)", ::isRed)
        compose.onNodeWithText("Choose a folder to move photos into").assertIsDisplayed()
        save("2-folder-open")

        vm.setTarget(Uri.fromFile(keepers), null)
        waitFor("the Keepers button") { vm.state.targets.size == 1 }
        compose.onNodeWithText("Move this photo to").assertIsDisplayed()
        compose.onNodeWithText("Keepers").assertIsDisplayed()
        save("3-keepers-added")

        // Swiping must change the photo on screen, not just its name.
        compose.onRoot().performTouchInput { swipeLeft() }
        waitFor("the second photo") { vm.state.index == 1 }
        waitForPhoto("green (IMG_2)", ::isGreen)
        save("4-swiped-to-2")

        compose.onRoot().performTouchInput { swipeRight() }
        waitForPhoto("red (IMG_1) again", ::isRed)
        compose.onRoot().performTouchInput { swipeLeft() }
        waitForPhoto("green (IMG_2) again", ::isGreen)

        // Tap the folder button: IMG_2 moves, and IMG_3 takes its place.
        compose.onNodeWithText("Keepers").performClick()
        waitFor("IMG_2 to be moved") { File(keepers, "IMG_2.jpg").exists() }
        assertFalse(File(camera, "IMG_2.jpg").exists())
        waitForPhoto("blue (IMG_3)", ::isBlue)
        assertEquals(listOf("IMG_1.jpg", "IMG_3.jpg"), vm.state.photos.map { it.name })
        save("5-moved")

        compose.onNodeWithContentDescription("Undo the last move").performClick()
        waitFor("IMG_2 to come back") { File(camera, "IMG_2.jpg").exists() }
        assertFalse(File(keepers, "IMG_2.jpg").exists())
        waitForPhoto("green (IMG_2) after undo", ::isGreen)
        save("6-undone")

        // Double-tap zooms in; Back returns to the whole photo.
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        Thread.sleep(600) // the full-resolution detail follows a moment after the zoom
        save("7-zoomed")

        assertTrue(File(camera, "IMG_1.jpg").exists() && File(camera, "IMG_3.jpg").exists())
        compose.onRoot().performTouchInput { doubleClick(center) } // and out again
        compose.waitForIdle()

        // Shuffle: the same photos in another order, from the top, files untouched.
        val names = listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg")
        compose.onNodeWithContentDescription("Shuffle the order").performClick()
        waitFor("the shuffle") { vm.state.shuffled }
        assertEquals(names.toSet(), vm.state.photos.map { it.name }.toSet())
        assertEquals(0, vm.state.index)
        save("8-shuffled")
        // Three photos can shuffle back into name order by chance: deal until they don't.
        repeat(20) {
            if (vm.state.photos.map { it.name } != names) return@repeat
            compose.onNodeWithContentDescription("Shuffle again").performClick()
            Thread.sleep(200)
            compose.waitForIdle()
        }
        assertTrue("never came out of name order", vm.state.photos.map { it.name } != names)
        assertEquals("shuffling must not touch the files", names, camera.list()!!.sorted())

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Back to name order").performClick()
        waitFor("name order") { !vm.state.shuffled }
        assertEquals(names, vm.state.photos.map { it.name })
    }
}
