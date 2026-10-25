package com.vortex.explorer.core

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A WebDAV server: Nextcloud, ownCloud, most NAS boxes, `rclone serve webdav`,
 * and Windows' own WebDAV publishing.
 *
 * Paths are `/`-separated and relative to [baseUrl]; the source handles all
 * percent-encoding, so names with spaces or accents work as typed.
 */
class WebDavSource(
    override val id: String,
    override val displayName: String,
    baseUrl: String,
    private val username: String? = null,
    private val password: String? = null,
) : FileSource {
    private val base: HttpUrl = normalizeBase(baseUrl)
    private val baseSegments: List<String> = base.pathSegments.filter { it.isNotEmpty() }
    // OkHttp stays an implementation detail: nothing in the public API exposes it.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override val rootPath: String = "/"
    override val capabilities: Set<Capability> = Capability.values().toSet()

    // -- reading --------------------------------------------------------

    override fun list(path: String): List<Entry> {
        val folder = Paths.normalize(path)
        val responses = propfind(urlFor(folder, isFolder = true), depth = 1)
        return responses.mapNotNull { (entryPath, props) ->
            if (entryPath == folder) return@mapNotNull null // the folder itself
            props.toEntry(id, entryPath, folder)
        }
    }

    override fun open(path: String): InputStream {
        val response = execute(request(urlFor(path)).get().build(), "open ${Paths.nameOf(path)}")
        val body = response.body ?: run {
            response.close()
            throw SourceException("the server sent nothing for ${Paths.nameOf(path)}")
        }
        return object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    // -- writing --------------------------------------------------------

    override fun write(
        parentPath: String,
        name: String,
        input: InputStream,
        length: Long,
        mime: String?,
        overwrite: Boolean,
    ): Entry {
        val path = Paths.join(parentPath, name)
        if (!overwrite && exists(path)) throw NameTakenException(name)
        val type = (mime ?: MediaTypes.mimeFor(name)).toMediaTypeOrNull()
        val body = object : RequestBody() {
            override fun contentType() = type
            override fun contentLength() = length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                input.source().use { sink.writeAll(it) }
            }
        }
        val builder = request(urlFor(path)).put(body)
        // Ask the server to refuse rather than replace, closing the race with the check above.
        if (!overwrite) builder.header("If-None-Match", "*")
        execute(builder.build(), "save $name", nameOnConflict = name).close()
        return Entry(id, path, name, false, length, System.currentTimeMillis(), mime, Paths.normalize(parentPath))
    }

    override fun mkdir(parentPath: String, name: String): Entry {
        val path = Paths.join(parentPath, name)
        val request = request(urlFor(path, isFolder = true)).method("MKCOL", null).build()
        execute(request, "create folder $name", nameOnConflict = name).close()
        return Entry(id, path, name, true, parent = Paths.normalize(parentPath))
    }

    override fun delete(entry: Entry) {
        if (Paths.normalize(entry.path) == "/") throw SourceException("will not delete the whole of $displayName")
        val request = request(urlFor(entry.path, entry.isFolder)).delete().build()
        execute(request, "delete ${entry.name}").close()
    }

    override fun rename(entry: Entry, newName: String): Entry {
        val path = Paths.join(Paths.parentOf(entry.path), newName)
        val request = request(urlFor(entry.path, entry.isFolder))
            .method("MOVE", null)
            .header("Destination", urlFor(path, entry.isFolder).toString())
            .header("Overwrite", "F")
            .build()
        execute(request, "rename ${entry.name}", nameOnConflict = newName).close()
        return entry.copy(path = path, name = newName)
    }

    override fun describe(path: String): String = urlFor(path).toString()

    /** Lists the root once; a cheap way to prove the address and login work. */
    fun check() {
        list(rootPath)
    }

    // -- plumbing -------------------------------------------------------

    private fun exists(path: String): Boolean {
        val request = request(urlFor(path)).head().build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 404 -> false
                    else -> throw failure(response.code, "check ${Paths.nameOf(path)}")
                }
            }
        } catch (e: IOException) {
            if (e is SourceException) throw e
            throw unreachable(e)
        }
    }

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        if (!username.isNullOrEmpty()) {
            builder.header("Authorization", Credentials.basic(username, password.orEmpty(), Charsets.UTF_8))
        }
        return builder
    }

    private fun execute(request: Request, what: String, nameOnConflict: String? = null): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw unreachable(e)
        }
        if (response.isSuccessful) return response
        val code = response.code
        response.close()
        if (nameOnConflict != null && (code == 405 || code == 412)) throw NameTakenException(nameOnConflict)
        throw failure(code, what)
    }

    private fun unreachable(e: IOException) =
        SourceException("could not reach ${base.host}: ${e.message ?: e.javaClass.simpleName}", e)

    private fun failure(code: Int, what: String): SourceException = SourceException(
        when (code) {
            401 -> "the server did not accept the user name or password"
            403 -> "the server refused to $what (forbidden)"
            404 -> "could not $what: not found on the server"
            409 -> "could not $what: its folder does not exist"
            423 -> "could not $what: the file is locked"
            507 -> "could not $what: the server is out of space"
            else -> "could not $what (HTTP $code)"
        },
    )

    private fun urlFor(path: String, isFolder: Boolean = false): HttpUrl {
        val builder = base.newBuilder()
        // Rebuild the path from segments so every name is encoded exactly once.
        builder.encodedPath("/")
        for (segment in baseSegments) builder.addPathSegment(segment)
        Paths.normalize(path).split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (isFolder) builder.addPathSegment("")
        return builder.build()
    }

    /** Maps a server href back to a path relative to the base URL. */
    private fun pathFromHref(href: String): String? {
        val resolved = base.resolve(href) ?: return null
        val segments = resolved.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < baseSegments.size || segments.subList(0, baseSegments.size) != baseSegments) {
            return null
        }
        return "/" + segments.drop(baseSegments.size).joinToString("/")
    }

    private fun propfind(url: HttpUrl, depth: Int): List<Pair<String, Props>> {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        val request = request(url)
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .build()
        val xml = execute(request, "list a folder").use { it.body?.string().orEmpty() }
        return parseMultistatus(xml)
    }

    private fun parseMultistatus(xml: String): List<Pair<String, Props>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A directory listing has no business declaring entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val document = try {
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SourceException("the server's folder listing could not be read", e)
        }
        val result = ArrayList<Pair<String, Props>>()
        val responses = document.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = response.child("href")?.textContent?.trim() ?: continue
            val path = pathFromHref(href) ?: continue
            result += path to Props.from(response)
        }
        return result
    }

    private class Props(
        val isFolder: Boolean,
        val size: Long,
        val modified: Long,
        val mime: String?,
        val displayName: String?,
    ) {
        fun toEntry(sourceId: String, path: String, parent: String): Entry {
            val name = Paths.nameOf(path).ifEmpty { displayName.orEmpty() }
            return Entry(
                sourceId = sourceId,
                path = path,
                name = name,
                isFolder = isFolder,
                size = if (isFolder) -1L else size,
                modified = modified,
                mime = mime,
                parent = parent,
            )
        }

        companion object {
            fun from(response: Element): Props {
                // A response can hold several propstats; only the 200 ones carry values.
                var isFolder = false
                var size = -1L
                var modified = 0L
                var mime: String? = null
                var display: String? = null
                val propstats = response.getElementsByTagNameNS(DAV, "propstat")
                for (i in 0 until propstats.length) {
                    val propstat = propstats.item(i) as Element
                    val status = propstat.child("status")?.textContent.orEmpty()
                    if (status.isNotEmpty() && !status.contains(" 200")) continue
                    val prop = propstat.child("prop") ?: continue
                    prop.child("resourcetype")?.let { type ->
                        if (type.getElementsByTagNameNS(DAV, "collection").length > 0) isFolder = true
                    }
                    prop.child("getcontentlength")?.textContent?.trim()?.toLongOrNull()?.let { size = it }
                    prop.child("getlastmodified")?.textContent?.trim()?.let { modified = parseDate(it) }
                    prop.child("getcontenttype")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { mime = it }
                    prop.child("displayname")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { display = it }
                }
                return Props(isFolder, size, modified, mime, display)
            }

            private fun parseDate(text: String): Long =
                runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
                    .getOrDefault(0L)
        }
    }

    private companion object {
        const val DAV = "DAV:"
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:getcontenttype/>
                <d:displayname/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        fun Element.child(localName: String): Element? {
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node is Element && node.namespaceURI == DAV && node.localName == localName) return node
            }
            return null
        }

        fun normalizeBase(url: String): HttpUrl {
            val trimmed = url.trim()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val parsed = withScheme.toHttpUrlOrNull()
                ?: throw SourceException("\"$url\" is not a web address")
            return parsed.newBuilder().query(null).fragment(null).build()
        }
    }
}
