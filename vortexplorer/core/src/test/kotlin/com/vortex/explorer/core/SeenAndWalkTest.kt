package com.vortex.explorer.core

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeenStoreTest {
    private val dir: File = Files.createTempDirectory("seen").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `marks and remembers`() {
        val store = SeenStore()
        assertFalse(store.isSeen("a"))
        store.markSeen(listOf("a", "b"))
        assertTrue(store.isSeen("a"))
        assertTrue(store.isSeen("b"))
        assertFalse(store.isSeen("c"))
        assertEquals(2, store.size)
    }

    @Test
    fun `survives a restart`() {
        val file = File(dir, "seen.txt")
        SeenStore(file).markSeen(listOf("phone|/DCIM/1.jpg", "nas|/photos/2.jpg"))
        val reopened = SeenStore(file)
        assertTrue(reopened.isSeen("phone|/DCIM/1.jpg"))
        assertTrue(reopened.isSeen("nas|/photos/2.jpg"))
        assertEquals(2, reopened.size)
    }

    @Test
    fun `stores hashes, not file names`() {
        val file = File(dir, "seen.txt")
        SeenStore(file).markSeen(listOf("phone|/DCIM/private-name.jpg"))
        assertFalse(file.readText().contains("private-name"))
    }

    @Test
    fun `marking twice writes once`() {
        val file = File(dir, "seen.txt")
        val store = SeenStore(file)
        store.markSeen(listOf("x"))
        store.markSeen(listOf("x"))
        assertEquals(1, file.readLines().count { it.isNotBlank() })
    }

    @Test
    fun `forget and clear`() {
        val file = File(dir, "seen.txt")
        val store = SeenStore(file)
        store.markSeen(listOf("a", "b", "c"))
        store.forget(listOf("b"))
        assertFalse(store.isSeen("b"))
        assertFalse(SeenStore(file).isSeen("b"))
        assertTrue(SeenStore(file).isSeen("a"))
        store.clear()
        assertEquals(0, store.size)
        assertEquals(0, SeenStore(file).size)
    }

    @Test
    fun `a damaged line is skipped, not fatal`() {
        val file = File(dir, "seen.txt")
        SeenStore(file).markSeen(listOf("good"))
        file.appendText("not-hex\n\n")
        assertTrue(SeenStore(file).isSeen("good"))
    }

    @Test
    fun `drives the unseen-first shuffle`() {
        val pool = library(20)
        val store = SeenStore()
        store.markSeen(pool.take(15).map { it.key })
        val result = Shuffler.shuffle(pool, ShuffleOptions(mode = ShuffleMode.UNSEEN_FIRST), store::isSeen)
        assertEquals(5, result.unseenCount)
        assertEquals(pool.drop(15).map { it.key }.toSet(), result.items.take(5).map { it.key }.toSet())
    }
}

class WalkerTest {
    private fun tree() = MemorySource()
        .file("/DCIM/a.jpg")
        .file("/DCIM/b.png")
        .file("/DCIM/clip.mp4")
        .file("/DCIM/notes.txt")
        .file("/DCIM/2024/c.jpg")
        .file("/DCIM/2024/deep/d.jpg")
        .file("/.thumbnails/t.jpg")
        .file("/DCIM/.hidden.jpg")

    @Test
    fun `collects matching files recursively`() {
        val result = Walker().collect(tree(), filter = EntryFilter.IMAGES)
        assertEquals(
            setOf("/DCIM/a.jpg", "/DCIM/b.png", "/DCIM/2024/c.jpg", "/DCIM/2024/deep/d.jpg"),
            result.items.map { it.path }.toSet(),
        )
        assertFalse(result.truncated)
        assertFalse(result.cancelled)
    }

    @Test
    fun `hidden files and folders are skipped unless asked for`() {
        val hidden = Walker().collect(tree(), filter = EntryFilter.IMAGES.copy(includeHidden = true))
        assertTrue(hidden.items.any { it.path == "/.thumbnails/t.jpg" })
        assertTrue(hidden.items.any { it.path == "/DCIM/.hidden.jpg" })
    }

    @Test
    fun `non-recursive stays in one folder`() {
        val result = Walker().collect(tree(), path = "/DCIM", filter = EntryFilter.MEDIA, recursive = false)
        assertEquals(setOf("a.jpg", "b.png", "clip.mp4"), result.items.map { it.name }.toSet())
    }

    @Test
    fun `filters by kind`() {
        assertEquals(listOf("clip.mp4"), Walker().collect(tree(), filter = EntryFilter.VIDEOS).items.map { it.name })
        assertTrue(Walker().collect(tree(), filter = EntryFilter.EVERYTHING).items.any { it.name == "notes.txt" })
    }

    @Test
    fun `an unreadable folder is reported and the walk carries on`() {
        val source = tree().apply { failingFolders += "/DCIM/2024" }
        val result = Walker().collect(source, filter = EntryFilter.IMAGES)
        assertEquals(setOf("a.jpg", "b.png"), result.items.map { it.name }.toSet())
        assertEquals(1, result.problems.size)
        assertTrue(result.problems.single().contains("permission denied"))
    }

    @Test
    fun `stops at the item cap`() {
        val source = MemorySource().apply { (0 until 50).forEach { file("/p/$it.jpg") } }
        val result = Walker(maxItems = 10).collect(source)
        assertEquals(10, result.items.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `can be cancelled`() {
        var calls = 0
        val result = Walker().collect(tree(), isCancelled = { ++calls > 2 })
        assertTrue(result.cancelled)
    }

    @Test
    fun `depth limit is respected`() {
        val result = Walker(maxDepth = 1).collect(tree(), filter = EntryFilter.IMAGES)
        assertFalse(result.items.any { it.name == "d.jpg" })
        assertTrue(result.items.any { it.name == "a.jpg" })
    }

    @Test
    fun `walks several sources into one pool`() {
        val phone = MemorySource(id = "phone").file("/DCIM/a.jpg").file("/DCIM/b.jpg")
        val usb = MemorySource(id = "usb").file("/Photos/a.jpg")
        val result = Walker().collect(listOf(Origin(phone), Origin(usb, "/Photos")), EntryFilter.IMAGES)
        assertEquals(setOf("phone|/DCIM/a.jpg", "phone|/DCIM/b.jpg", "usb|/Photos/a.jpg"), result.items.map { it.key }.toSet())
        val shuffled = Shuffler.shuffle(result.items, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD))
        assertEquals(3, shuffled.items.size)
    }

    @Test
    fun `reports progress`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        Walker().collect(tree(), filter = EntryFilter.IMAGES) { folders, files -> seen += folders to files }
        assertTrue(seen.isNotEmpty())
        assertEquals(4, seen.last().second)
    }
}

class PathsTest {
    @Test
    fun `normalize, join, parent and name`() {
        assertEquals("/a/b", Paths.normalize("a//b/"))
        assertEquals("/a", Paths.normalize("/a/b/.."))
        assertEquals("/", Paths.normalize("/../.."))
        assertEquals("/b", Paths.normalize("\\a\\..\\b"))
        assertEquals("/x/y.jpg", Paths.join("/x", "y.jpg"))
        assertEquals("/y.jpg", Paths.join("/", "y.jpg"))
        assertEquals("/x", Paths.parentOf("/x/y.jpg"))
        assertEquals("/", Paths.parentOf("/x"))
        assertEquals("y.jpg", Paths.nameOf("/x/y.jpg"))
    }

    @Test
    fun `names that would escape their folder are refused`() {
        for (bad in listOf("", " ", ".", "..", "a/b", "a\\b", "nul\u0000")) {
            kotlin.test.assertFailsWith<SourceException>("accepted \"$bad\"") { Paths.join("/x", bad) }
        }
    }

    @Test
    fun `media kinds come from extension, then mime`() {
        assertEquals(EntryKind.IMAGE, MediaTypes.kindOf("A.JPG"))
        assertEquals(EntryKind.VIDEO, MediaTypes.kindOf("clip.mkv"))
        assertEquals(EntryKind.AUDIO, MediaTypes.kindOf("song.flac"))
        assertEquals(EntryKind.IMAGE, MediaTypes.kindOf("noext", "image/png"))
        assertEquals(EntryKind.OTHER, MediaTypes.kindOf("readme"))
        assertEquals("image/jpeg", MediaTypes.mimeFor("x.JPEG"))
        assertEquals("application/octet-stream", MediaTypes.mimeFor("x.weird"))
    }
}
