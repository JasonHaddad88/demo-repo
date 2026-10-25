package com.vortex.explorer.core

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One behaviour, every source. Each concrete subclass runs these against a
 * real backend: a temp folder always, and a real WebDAV / Samba server when
 * the VORTEX_* environment variables describe one.
 */
abstract class SourceContractTest {
    protected lateinit var source: FileSource
    protected lateinit var work: String

    abstract fun connect(): FileSource

    @BeforeTest
    fun setUp() {
        source = connect()
        work = source.mkdir(source.rootPath, "vx-test-${Seeds.fresh().lowercase()}").path
    }

    @AfterTest
    fun tearDown() {
        if (::source.isInitialized) {
            runCatching {
                source.list(source.rootPath).filter { it.path == work }.forEach(source::delete)
            }
            source.close()
        }
    }

    private fun put(name: String, text: String, folder: String = work): Entry {
        val bytes = text.toByteArray()
        return source.write(folder, name, bytes.inputStream(), bytes.size.toLong())
    }

    private fun read(path: String) = source.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun names(folder: String = work) = source.list(folder).map { it.name }.toSet()

    @Test
    fun `write, list and read back`() {
        put("a.jpg", "alpha")
        source.mkdir(work, "sub")
        val listing = source.list(work)
        assertEquals(setOf("a.jpg", "sub"), listing.map { it.name }.toSet())
        val file = listing.single { it.name == "a.jpg" }
        assertFalse(file.isFolder)
        assertEquals(5L, file.size)
        assertEquals(source.id, file.sourceId)
        assertEquals(work, file.parent)
        assertTrue(listing.single { it.name == "sub" }.isFolder)
        assertEquals("alpha", read(file.path))
    }

    @Test
    fun `awkward names survive the round trip`() {
        val awkward = listOf("with space.jpg", "café ñ.png", "100% & more.gif", "[brackets] (1).mp4", "#hash.txt")
        awkward.forEach { put(it, it) }
        assertEquals(awkward.toSet(), names())
        for (entry in source.list(work)) assertEquals(entry.name, read(entry.path))
    }

    @Test
    fun `large files stream intact`() {
        val bytes = ByteArray(3 * 1024 * 1024 + 17) { (it * 31 + 7).toByte() }
        source.write(work, "big.bin", bytes.inputStream(), bytes.size.toLong())
        val back = source.open(source.list(work).single().path).use { it.readBytes() }
        assertContentEquals(bytes, back)
    }

    @Test
    fun `an existing name is refused unless overwriting`() {
        put("a.jpg", "first")
        assertFailsWith<NameTakenException> { put("a.jpg", "second") }
        assertEquals("first", read(source.list(work).single().path))
        val bytes = "third".toByteArray()
        source.write(work, "a.jpg", bytes.inputStream(), bytes.size.toLong(), overwrite = true)
        assertEquals("third", read(source.list(work).single().path))
    }

    @Test
    fun `an existing folder is refused`() {
        source.mkdir(work, "sub")
        assertFailsWith<NameTakenException> { source.mkdir(work, "sub") }
    }

    @Test
    fun `rename files and folders`() {
        val file = put("old.jpg", "x")
        val renamed = source.rename(file, "new.jpg")
        assertEquals("new.jpg", renamed.name)
        assertEquals(setOf("new.jpg"), names())
        assertEquals("x", read(renamed.path))

        val folder = source.mkdir(work, "dir")
        put("inner.jpg", "y", folder.path)
        val movedFolder = source.rename(folder, "dir2")
        assertEquals(setOf("inner.jpg"), names(movedFolder.path))
    }

    @Test
    fun `rename onto an existing name is refused`() {
        val a = put("a.jpg", "a")
        put("b.jpg", "b")
        assertFailsWith<NameTakenException> { source.rename(a, "b.jpg") }
        assertEquals("b", read(source.list(work).single { it.name == "b.jpg" }.path))
    }

    @Test
    fun `delete files and whole folders`() {
        val file = put("a.jpg", "a")
        val folder = source.mkdir(work, "dir")
        put("inner.jpg", "y", folder.path)
        source.mkdir(folder.path, "deeper")
        source.delete(file)
        source.delete(folder)
        assertEquals(emptySet(), names())
    }

    @Test
    fun `nested folders walk and shuffle`() {
        val a = source.mkdir(work, "a")
        val b = source.mkdir(a.path, "b")
        put("1.jpg", "1", a.path)
        put("2.jpg", "2", b.path)
        put("3.png", "3", work)
        put("notes.txt", "t", work)
        val walk = Walker().collect(source, work, EntryFilter.IMAGES)
        assertEquals(setOf("1.jpg", "2.jpg", "3.png"), walk.items.map { it.name }.toSet())
        val order = Shuffler.shuffle(walk.items, ShuffleOptions(mode = ShuffleMode.FOLDER_SPREAD))
        assertEquals(3, order.items.size)
    }

    @Test
    fun `missing paths fail with a readable message`() {
        val error = assertFailsWith<SourceException> { source.list(Paths.join(work, "nope")) }
        assertTrue(error.message!!.isNotBlank())
        assertFailsWith<SourceException> { source.open(Paths.join(work, "nope.jpg")).use { it.read() } }
    }

    @Test
    fun `copy here from memory and back`() {
        val memory = MemorySource(id = "mem").file("/in/a.jpg", "AAA").file("/in/sub/b.jpg", "BBB")
        val sources = mapOf(memory.id to memory, source.id to source)
        val report = Copier(sources::get).copy(memory.list("/in"), source, work)
        assertTrue(report.ok, report.failed.toString())
        assertEquals(setOf("a.jpg", "sub"), names())
        val sub = source.list(work).single { it.name == "sub" }
        assertEquals("BBB", read(source.list(sub.path).single().path))

        val back = MemorySource(id = "back")
        val toMemory = Copier((sources + (back.id to back))::get).copy(source.list(work), back, "/")
        assertTrue(toMemory.ok, toMemory.failed.toString())
        assertEquals("AAA", back.read("/a.jpg"))
        assertEquals("BBB", back.read("/sub/b.jpg"))
    }

    @Test
    fun `shuffled export numbers files and writes a playlist`() {
        val memory = MemorySource(id = "mem")
            .file("/m/song1.mp3", "one").file("/m/song2.mp3", "two").file("/m/song3.mp3", "three")
        val order = Shuffler.shuffle(memory.list("/m"), ShuffleOptions(seed = "car")).items
        val report = Exporter.export(order, mapOf(memory.id to memory)::get, source, work)
        assertTrue(report.ok, report.failed.toString())
        val listing = names()
        val expected = order.mapIndexed { i, e -> "%03d - %s".format(i + 1, e.name) }
        assertEquals((expected + "shuffle.m3u").toSet(), listing)
        val playlist = read(source.list(work).single { it.name == "shuffle.m3u" }.path)
        assertEquals(listOf("#EXTM3U") + expected, playlist.lines().filter { it.isNotBlank() })
    }

    @Test
    fun `reshuffle in place renames without copying`() {
        listOf("a.mp3", "b.mp3", "c.mp3", "d.mp3").forEach { put(it, it) }
        val first = Shuffler.shuffle(source.list(work), ShuffleOptions(seed = "one")).items
        val report = Exporter.reshuffleInPlace(first, source)
        assertTrue(report.ok, report.failed.toString())
        val after = source.list(work)
        assertEquals(4, after.size)
        assertTrue(after.all { Regex("""^\d{3} - [a-d]\.mp3$""").matches(it.name) }, after.map { it.name }.toString())
        // Contents follow their names.
        for (entry in after) assertEquals(Exporter.stripNumber(entry.name), read(entry.path))

        // Doing it again re-numbers instead of stacking prefixes.
        val second = Shuffler.shuffle(after, ShuffleOptions(seed = "two")).items
        assertTrue(Exporter.reshuffleInPlace(second, source).ok)
        val again = source.list(work).map { it.name }
        assertTrue(again.all { Regex("""^\d{3} - [a-d]\.mp3$""").matches(it) }, again.toString())
        assertEquals(second.map { Exporter.stripNumber(it.name) }, again.sorted().map { Exporter.stripNumber(it) })
    }
}

class LocalSourceTest : SourceContractTest() {
    private val root: File = Files.createTempDirectory("vx-local").toFile()

    override fun connect(): FileSource = LocalSource("local", "Local", root)

    @AfterTest
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun `cannot escape its root`() {
        // ".." is clamped at the root: "/../../etc" is a folder *inside* the root.
        assertEquals("/etc", Paths.normalize("/../../etc"))
        assertFailsWith<SourceException> { source.write(work, "../../escape.txt", "x".byteInputStream()) }
        // Climbing past the root lands on the root itself, never above it.
        assertEquals(source.list("/").map { it.name }.toSet(), source.list("/../../..").map { it.name }.toSet())
    }

    @Test
    fun `a failed write leaves no partial file`() {
        val failing = object : java.io.InputStream() {
            var sent = 0
            override fun read(): Int {
                if (sent++ > 1000) throw java.io.IOException("connection dropped")
                return 1
            }
        }
        assertFailsWith<java.io.IOException> { source.write(work, "half.bin", failing) }
        assertEquals(emptyList(), source.list(work).map { it.name })
    }
}

class WebDavSourceTest : SourceContractTest() {
    override fun connect(): FileSource {
        val url = System.getenv("VORTEX_WEBDAV_URL")
        assumeTrue("set VORTEX_WEBDAV_URL to run against a real WebDAV server", !url.isNullOrBlank())
        return WebDavSource(
            id = "dav",
            displayName = "WebDAV",
            baseUrl = url,
            username = System.getenv("VORTEX_WEBDAV_USER"),
            password = System.getenv("VORTEX_WEBDAV_PASSWORD"),
        )
    }

    @Test
    fun `a wrong password is reported as such`() {
        val wrong = WebDavSource("dav", "WebDAV", System.getenv("VORTEX_WEBDAV_URL"), System.getenv("VORTEX_WEBDAV_USER") ?: "x", "definitely-wrong")
        val error = assertFailsWith<SourceException> { wrong.check() }
        assertTrue(error.message!!.contains("user name or password"), error.message)
    }
}

class SmbSourceTest : SourceContractTest() {
    override fun connect(): FileSource {
        val host = System.getenv("VORTEX_SMB_HOST")
        assumeTrue("set VORTEX_SMB_HOST to run against a real SMB server", !host.isNullOrBlank())
        return SmbSource(
            id = "smb",
            displayName = "SMB",
            host = host,
            share = System.getenv("VORTEX_SMB_SHARE") ?: "share",
            username = System.getenv("VORTEX_SMB_USER").orEmpty(),
            password = System.getenv("VORTEX_SMB_PASSWORD").orEmpty(),
            port = System.getenv("VORTEX_SMB_PORT")?.toIntOrNull() ?: SmbSource.DEFAULT_PORT,
        )
    }

    private fun smb(password: String = System.getenv("VORTEX_SMB_PASSWORD").orEmpty(), share: String? = null) = SmbSource(
        id = "smb2",
        displayName = "SMB",
        host = System.getenv("VORTEX_SMB_HOST"),
        share = share ?: (System.getenv("VORTEX_SMB_SHARE") ?: "share"),
        username = System.getenv("VORTEX_SMB_USER").orEmpty(),
        password = password,
        port = System.getenv("VORTEX_SMB_PORT")?.toIntOrNull() ?: SmbSource.DEFAULT_PORT,
    )

    @Test
    fun `a wrong password is reported as such`() {
        val error = assertFailsWith<SourceException> { smb(password = "definitely-wrong").use { it.check() } }
        assertTrue(error.message!!.contains("user name or password"), error.message)
    }

    @Test
    fun `a missing share is named`() {
        val error = assertFailsWith<SourceException> { smb(share = "no-such-share").use { it.check() } }
        assertTrue(error.message!!.contains("no share called"), error.message)
    }

    @Test
    fun `describes locations as smb urls`() {
        assertTrue(source.describe(work).startsWith("smb://"))
    }
}
