package com.vortex.explorer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferTest {
    private fun pair(): Pair<MemorySource, MemorySource> {
        val from = MemorySource(id = "from")
            .file("/p/a.jpg", "AAA")
            .file("/p/b.jpg", "BB")
            .file("/p/album/c.jpg", "C")
            .file("/p/album/deep/d.jpg", "DDDD")
        val to = MemorySource(id = "to").folder("/dest")
        return from to to
    }

    private fun copier(vararg sources: MemorySource) = Copier(sources.associateBy { it.id }::get)

    @Test
    fun `copies files and folders with their contents`() {
        val (from, to) = pair()
        val report = copier(from, to).copy(from.list("/p"), to, "/dest")
        assertTrue(report.ok)
        assertEquals("AAA", to.read("/dest/a.jpg"))
        assertEquals("C", to.read("/dest/album/c.jpg"))
        assertEquals("DDDD", to.read("/dest/album/deep/d.jpg"))
        assertTrue(from.exists("/p/a.jpg"), "copy must not remove the original")
    }

    @Test
    fun `rename on conflict keeps both`() {
        val (from, to) = pair()
        to.file("/dest/a.jpg", "OLD")
        copier(from, to).copy(listOf(from.list("/p").single { it.name == "a.jpg" }), to, "/dest")
        assertEquals("OLD", to.read("/dest/a.jpg"))
        assertEquals("AAA", to.read("/dest/a (2).jpg"))
    }

    @Test
    fun `skip on conflict leaves the existing file`() {
        val (from, to) = pair()
        to.file("/dest/a.jpg", "OLD")
        val report = copier(from, to).copy(from.list("/p").filter { !it.isFolder }, to, "/dest", Conflict.SKIP)
        assertEquals("OLD", to.read("/dest/a.jpg"))
        assertEquals(listOf("a.jpg"), report.skipped.map { it.name })
        assertEquals("BB", to.read("/dest/b.jpg"))
    }

    @Test
    fun `overwrite on conflict replaces`() {
        val (from, to) = pair()
        to.file("/dest/a.jpg", "OLD")
        copier(from, to).copy(listOf(from.list("/p").single { it.name == "a.jpg" }), to, "/dest", Conflict.OVERWRITE)
        assertEquals("AAA", to.read("/dest/a.jpg"))
        assertFalse(to.exists("/dest/a (2).jpg"))
    }

    @Test
    fun `existing folders are merged unless renaming`() {
        val (from, to) = pair()
        to.file("/dest/album/old.jpg", "OLD")
        copier(from, to).copy(listOf(from.list("/p").single { it.isFolder }), to, "/dest", Conflict.SKIP)
        assertEquals("OLD", to.read("/dest/album/old.jpg"))
        assertEquals("C", to.read("/dest/album/c.jpg"))

        copier(from, to).copy(listOf(from.list("/p").single { it.isFolder }), to, "/dest", Conflict.RENAME)
        assertEquals("C", to.read("/dest/album (2)/c.jpg"))
    }

    @Test
    fun `move removes the originals`() {
        val (from, to) = pair()
        val report = copier(from, to).copy(from.list("/p"), to, "/dest", move = true)
        assertTrue(report.ok)
        assertEquals(emptyList(), from.list("/p").map { it.name })
        assertEquals("DDDD", to.read("/dest/album/deep/d.jpg"))
    }

    @Test
    fun `failures are reported per file and the rest still copies`() {
        val (from, to) = pair()
        val ghost = Entry("from", "/p/ghost.jpg", "ghost.jpg", false, 3, parent = "/p")
        val report = copier(from, to).copy(from.list("/p").filter { !it.isFolder } + ghost, to, "/dest")
        assertFalse(report.ok)
        assertEquals(listOf("ghost.jpg"), report.failed.map { it.first.name })
        assertEquals("AAA", to.read("/dest/a.jpg"))
        assertFalse(to.exists("/dest/ghost.jpg"), "a failed copy must not leave a stub behind")
    }

    @Test
    fun `an unknown source is reported, not thrown`() {
        val (_, to) = pair()
        val orphan = Entry("gone", "/x.jpg", "x.jpg", false, parent = "/")
        val report = copier(to).copy(listOf(orphan), to, "/dest")
        assertEquals("its source is no longer connected", report.failed.single().second)
    }

    @Test
    fun `cancellation stops the copy`() {
        val (from, to) = pair()
        var checks = 0
        val report = copier(from, to).copy(from.list("/p"), to, "/dest", isCancelled = { ++checks > 2 })
        assertTrue(report.cancelled)
    }

    @Test
    fun `progress ends at the total`() {
        val (from, to) = pair()
        val updates = mutableListOf<TransferProgress>()
        copier(from, to).copy(from.list("/p").filter { !it.isFolder }, to, "/dest", onProgress = { updates += it })
        val last = updates.last()
        assertEquals(2, last.filesDone)
        assertEquals(2, last.filesTotal)
        assertEquals(5L, last.bytesDone)
        assertEquals(5L, last.bytesTotal)
    }

    @Test
    fun `a read-only destination is refused up front`() {
        val (from, _) = pair()
        val gallery = MemorySource(id = "ro", displayName = "Gallery", readOnly = true)
        assertFailsWith<ReadOnlyException> { copier(from, gallery).copy(from.list("/p"), gallery, "/") }
    }

    @Test
    fun `names unsafe for a USB stick are cleaned on the way`() {
        val from = MemorySource(id = "from").file("/p/what? a: day.jpg", "x")
        val to = MemorySource(id = "to").folder("/d")
        copier(from, to).copy(from.list("/p"), to, "/d")
        assertEquals("x", to.read("/d/what_ a_ day.jpg"))
    }
}

class ExportPlanTest {
    @Test
    fun `numbers follow the shuffled order`() {
        val order = listOf(entry("/x/c.jpg"), entry("/x/a.jpg"), entry("/x/b.jpg"))
        val plan = Exporter.plan(order)
        assertEquals(listOf("001 - c.jpg", "002 - a.jpg", "003 - b.jpg"), plan.targets.map { it.second })
        assertNull(plan.playlistName, "images need no playlist")
    }

    @Test
    fun `width grows with the count`() {
        val plan = Exporter.plan(library(1500))
        assertTrue(plan.targets.first().second.startsWith("0001 - "))
        assertTrue(plan.targets.last().second.startsWith("1500 - "))
    }

    @Test
    fun `bare numbers keep the extension`() {
        val plan = Exporter.plan(listOf(entry("/x/Song.MP3"), entry("/x/noext")), ExportOptions(keepNames = false))
        assertEquals(listOf("001.mp3", "002"), plan.targets.map { it.second })
    }

    @Test
    fun `our own prefixes are replaced, not stacked`() {
        val plan = Exporter.plan(listOf(entry("/x/007 - song.mp3"), entry("/x/0042 - other.mp3")))
        assertEquals(listOf("001 - song.mp3", "002 - other.mp3"), plan.targets.map { it.second })
        assertEquals("song.mp3", Exporter.stripNumber("003 - song.mp3"))
        assertEquals("song.mp3", Exporter.stripNumber("003_song.mp3", separator = "_"))
    }

    @Test
    fun `names that merely start with digits are left alone`() {
        for (name in listOf("2024 holiday.jpg", "12_track.mp3", "01 intro.mp3", "99 - two digits.mp3", "123.jpg")) {
            assertEquals(name, Exporter.stripNumber(name), "ate part of \"$name\"")
        }
        val plan = Exporter.plan(listOf(entry("/x/2024 holiday.jpg")))
        assertEquals("001 - 2024 holiday.jpg", plan.targets.single().second)
    }

    @Test
    fun `audio and video get a playlist`() {
        assertEquals("shuffle.m3u", Exporter.plan(listOf(entry("/x/a.mp3"))).playlistName)
        assertNull(Exporter.plan(listOf(entry("/x/a.mp3")), ExportOptions(playlist = false)).playlistName)
    }

    @Test
    fun `folders in the order are ignored`() {
        val folder = Entry("test", "/x/dir", "dir", isFolder = true, parent = "/x")
        assertEquals(1, Exporter.plan(listOf(folder, entry("/x/a.jpg"))).targets.size)
    }
}

class NamesTest {
    @Test
    fun `unsafe characters and reserved names`() {
        assertEquals("a_b_c_.jpg", Names.safe("a:b?c*.jpg"))
        assertEquals("_CON.txt", Names.safe("CON.txt"))
        assertEquals("trailing", Names.safe("trailing. . "))
        assertEquals("file", Names.safe("..."))
    }

    @Test
    fun `long names are cut but keep their extension`() {
        val safe = Names.safe("x".repeat(400) + ".jpeg")
        assertTrue(safe.endsWith(".jpeg"))
        assertTrue(safe.toByteArray().size <= 200)
    }

    @Test
    fun `unique counts up and ignores case`() {
        assertEquals("a.jpg", Names.unique("a.jpg", setOf("b.jpg")))
        assertEquals("a (2).jpg", Names.unique("a.jpg", setOf("A.JPG")))
        assertEquals("a (3).jpg", Names.unique("a.jpg", setOf("a.jpg", "a (2).jpg")))
        assertEquals("README (2)", Names.unique("README", setOf("README")))
    }
}
