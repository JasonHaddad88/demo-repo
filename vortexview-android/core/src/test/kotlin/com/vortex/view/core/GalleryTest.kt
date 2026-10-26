package com.vortex.view.core

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val storage = FileStorage()

    private fun folder(name: String, vararg files: String): Folder {
        val dir = File(temp.root, name).apply { mkdirs() }
        files.forEach { File(dir, it).writeText("pixels of $it") }
        return storage.folder(dir)
    }

    private fun names(folder: Folder) = File(folder.id).list()!!.sorted()

    @Test
    fun `lists only photos, in natural order, skipping hidden and trashed files`() {
        val camera = folder(
            "Camera",
            "IMG_10.jpg", "IMG_2.JPG", "IMG_1.heic", "notes.txt", ".nomedia",
            ".trashed-1700000000-IMG_3.jpg", ".pending-1700000000-IMG_4.jpg", "clip.mp4",
        )
        File(File(camera.id), "Sub.jpg").mkdir() // a folder with a photo-like name
        val gallery = Gallery(storage)
        gallery.open(camera)
        assertEquals(listOf("IMG_1.heic", "IMG_2.JPG", "IMG_10.jpg"), gallery.photos.map { it.name })
        assertEquals("IMG_1.heic", gallery.current?.name)
    }

    @Test
    fun `opens at the photo it was on last time`() {
        val gallery = Gallery(storage)
        gallery.open(folder("Camera", "a.jpg", "b.jpg", "c.jpg"), startAt = "b.jpg")
        assertEquals("b.jpg", gallery.current?.name)
        gallery.open(folder("Other", "x.jpg"), startAt = "gone.jpg")
        assertEquals("x.jpg", gallery.current?.name)
    }

    @Test
    fun `moving takes the photo out and the next one takes its place`() {
        val camera = folder("Camera", "a.jpg", "b.jpg", "c.jpg")
        val keep = folder("Keepers")
        val gallery = Gallery(storage)
        gallery.open(camera)
        gallery.go(1)

        val move = gallery.moveCurrent(keep)
        assertEquals("b.jpg", move.moved.name)
        assertFalse(move.renamed)
        assertEquals(listOf("b.jpg"), names(keep))
        assertEquals(listOf("a.jpg", "c.jpg"), names(camera))
        assertEquals("c.jpg", gallery.current?.name)

        gallery.moveCurrent(keep) // the last one: we step back to the one before
        assertEquals("a.jpg", gallery.current?.name)
        gallery.moveCurrent(keep)
        assertNull(gallery.current)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names(keep))
    }

    @Test
    fun `never overwrites a photo already in the destination`() {
        val camera = folder("Camera", "IMG_1.jpg")
        val keep = folder("Keepers", "IMG_1.jpg", "img_1 (2).jpg")
        val gallery = Gallery(storage)
        gallery.open(camera)
        val move = gallery.moveCurrent(keep)
        assertTrue(move.renamed)
        assertEquals("IMG_1 (3).jpg", move.moved.name)
        assertEquals("pixels of IMG_1.jpg", File(File(keep.id), "IMG_1.jpg").readText())
    }

    @Test
    fun `undo puts it back in its place and shows it again`() {
        val camera = folder("Camera", "a.jpg", "b.jpg", "c.jpg")
        val keep = folder("Keepers")
        val gallery = Gallery(storage)
        gallery.open(camera)
        gallery.go(1)
        gallery.moveCurrent(keep)
        gallery.moveCurrent(keep)
        assertTrue(gallery.canUndo)

        assertEquals("c.jpg", gallery.undo()?.moved?.name)
        assertEquals("c.jpg", gallery.current?.name)
        assertEquals("b.jpg", gallery.undo()?.moved?.name)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), gallery.photos.map { it.name })
        assertEquals("b.jpg", gallery.current?.name)
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), names(camera))
        assertFalse(gallery.canUndo)
        assertNull(gallery.undo())
    }

    @Test
    fun `undo works after opening another folder`() {
        val camera = folder("Camera", "a.jpg")
        val keep = folder("Keepers")
        val gallery = Gallery(storage)
        gallery.open(camera)
        gallery.moveCurrent(keep)
        gallery.open(folder("Elsewhere", "z.jpg"))
        gallery.undo()
        assertEquals(listOf("a.jpg"), names(camera))
        assertEquals("z.jpg", gallery.current?.name, "the open folder is left as it was")
    }

    @Test
    fun `undo never overwrites a new photo with the same name`() {
        val camera = folder("Camera", "a.jpg")
        val keep = folder("Keepers")
        val gallery = Gallery(storage)
        gallery.open(camera)
        gallery.moveCurrent(keep)
        File(File(camera.id), "a.jpg").writeText("a new photo")
        assertEquals("a (2).jpg", gallery.undo()?.moved?.name)
        assertEquals("a new photo", File(File(camera.id), "a.jpg").readText())
    }

    @Test
    fun `undo explains when the moved photo has gone`() {
        val camera = folder("Camera", "a.jpg")
        val keep = folder("Keepers")
        val gallery = Gallery(storage)
        gallery.open(camera)
        gallery.moveCurrent(keep)
        File(File(keep.id), "a.jpg").delete()
        val error = assertFailsWith<MoveException> { gallery.undo() }
        assertTrue("no longer in Keepers" in error.message!!, error.message)
        assertFalse(gallery.canUndo)
    }

    @Test
    fun `refuses to move into the same folder or a missing one`() {
        val camera = folder("Camera", "a.jpg")
        val gallery = Gallery(storage)
        gallery.open(camera)
        assertFailsWith<MoveException> { gallery.moveCurrent(camera) }
        val missing = Folder(File(temp.root, "Gone").absolutePath, "Gone")
        val error = assertFailsWith<MoveException> { gallery.moveCurrent(missing) }
        assertTrue("isn't there any more" in error.message!!)
        assertEquals(listOf("a.jpg"), names(camera))
    }

    @Test
    fun `reload keeps the current photo`() {
        val camera = folder("Camera", "b.jpg", "c.jpg")
        val gallery = Gallery(storage)
        gallery.open(camera, startAt = "c.jpg")
        File(File(camera.id), "a.jpg").writeText("new")
        gallery.reload()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), gallery.photos.map { it.name })
        assertEquals("c.jpg", gallery.current?.name)
    }

    @Test
    fun `stepping stops at the ends`() {
        val gallery = Gallery(storage)
        gallery.open(folder("Camera", "a.jpg", "b.jpg"))
        assertFalse(gallery.go(-1))
        assertTrue(gallery.go(5))
        assertEquals("b.jpg", gallery.current?.name)
        assertEquals("a.jpg", gallery.neighbour(-1)?.name)
        assertNull(gallery.neighbour(1))
    }
}

class NamesTest {
    @Test
    fun `natural order`() {
        val sorted = listOf("IMG_10.jpg", "img_9.jpg", "IMG_2.jpg", "IMG_002.jpg", "a.jpg", "IMG_1 (2).jpg", "IMG_1.jpg")
            .sortedWith(Names.naturalOrder)
        assertEquals(listOf("a.jpg", "IMG_1 (2).jpg", "IMG_1.jpg", "IMG_002.jpg", "IMG_2.jpg", "img_9.jpg", "IMG_10.jpg"), sorted)
    }

    @Test
    fun `very long numbers do not overflow`() {
        val sorted = listOf("PXL_20240101_123456789012345678.jpg", "PXL_20240101_99.jpg").sortedWith(Names.naturalOrder)
        assertEquals("PXL_20240101_99.jpg", sorted.first())
    }

    @Test
    fun `photos by extension or by type`() {
        assertTrue(Names.isPhoto("x.JPG"))
        assertTrue(Names.isPhoto("x.heic"))
        assertTrue(Names.isPhoto("download", "image/jpeg"))
        assertFalse(Names.isPhoto("clip.mp4", "video/mp4"))
        assertFalse(Names.isPhoto(".hidden.jpg"))
        assertFalse(Names.isPhoto("notes.txt", "image/png"), "a telling extension wins")
    }

    @Test
    fun `unique names`() {
        assertEquals("a.jpg", Names.unique("a.jpg", setOf("b.jpg")))
        assertEquals("a (2).jpg", Names.unique("a.jpg", setOf("a.jpg")))
        assertEquals("A (3).JPG", Names.unique("A.JPG", setOf("a.jpg", "a (2).jpg")))
        assertEquals("README (2)", Names.unique("README", setOf("readme")))
        assertEquals(".x (2)", Names.unique(".x", setOf(".x")))
    }
}

class ShuffleTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val storage = FileStorage()
    private val names = (1..40).map { "IMG_$it.jpg" }

    private fun camera(): Folder {
        val dir = File(temp.root, "Camera").apply { mkdirs() }
        names.forEach { File(dir, it).writeText(it) }
        return storage.folder(dir)
    }

    private fun Gallery.names() = photos.map { it.name }

    @Test
    fun `shuffling changes the order and starts from the top`() {
        val gallery = Gallery(storage)
        gallery.open(camera())
        gallery.go(5)
        gallery.shuffle(seed = 1)
        assertEquals(names.toSet(), gallery.names().toSet(), "the same photos, no more, no fewer")
        assertTrue(gallery.names() != names, "a shuffle of 40 should not come out in name order")
        assertEquals(0, gallery.index)
        assertEquals(Order.Shuffled(1), gallery.order)
    }

    @Test
    fun `the same seed gives the same order, another seed another`() {
        val a = Gallery(storage).apply { open(camera()); shuffle(seed = 7) }
        val b = Gallery(storage).apply { open(camera()); shuffle(seed = 7) }
        val c = Gallery(storage).apply { open(camera()); shuffle(seed = 8) }
        assertEquals(a.names(), b.names())
        assertTrue(a.names() != c.names())
    }

    @Test
    fun `moving a photo out leaves the rest of the shuffle as it was`() {
        val gallery = Gallery(storage)
        gallery.open(camera())
        gallery.shuffle(seed = 3)
        val before = gallery.names()
        gallery.go(4)
        val moving = gallery.current!!.name
        gallery.moveCurrent(storage.folder(File(temp.root, "Keepers").apply { mkdirs() }))
        assertEquals(before - moving, gallery.names())
        assertEquals(before[5], gallery.current?.name, "the next photo in the shuffle takes its place")

        gallery.undo()
        assertEquals(before, gallery.names(), "undo puts it back in its place in the shuffle")
        assertEquals(moving, gallery.current?.name)
    }

    @Test
    fun `reopening with the same seed carries on where you were`() {
        val folder = camera()
        val first = Gallery(storage).apply { open(folder); shuffle(seed = 11); go(9) }
        val again = Gallery(storage).apply { useOrder(Order.Shuffled(11)); open(folder, startAt = first.current?.name) }
        assertEquals(first.names(), again.names())
        assertEquals(9, again.index)
    }

    @Test
    fun `a photo added later lands somewhere in the shuffle, and the rest keep their order`() {
        val folder = camera()
        val gallery = Gallery(storage)
        gallery.open(folder)
        gallery.shuffle(seed = 5)
        val before = gallery.names()
        File(File(folder.id), "IMG_new.jpg").writeText("new")
        gallery.reload()
        assertEquals(before, gallery.names() - "IMG_new.jpg")
        assertTrue("IMG_new.jpg" in gallery.names())
    }

    @Test
    fun `back to name order stays on the same photo`() {
        val gallery = Gallery(storage)
        gallery.open(camera())
        gallery.shuffle(seed = 2)
        gallery.go(3)
        val looking = gallery.current?.name
        gallery.sortByName()
        assertEquals(names.sortedWith(Names.naturalOrder), gallery.names())
        assertEquals(looking, gallery.current?.name)
        assertEquals(Order.ByName, gallery.order)
    }

    @Test
    fun `a shuffle is a fair one`() {
        // Where IMG_1 lands over many shuffles: every position should come up.
        val gallery = Gallery(storage)
        gallery.open(camera())
        val seen = HashSet<Int>()
        for (seed in 0L until 2000L) {
            gallery.shuffle(seed)
            seen += gallery.names().indexOf("IMG_1.jpg")
        }
        assertEquals(names.size, seen.size, "IMG_1 should be able to land anywhere")
    }
}
