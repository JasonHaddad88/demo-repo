package com.vortex.explorer.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A Windows or Samba file share (SMB 2/3) — a shared folder on a PC, or a NAS.
 *
 * Paths are `/`-separated and relative to the share; [basePath] lets a source
 * start inside a folder of the share instead of at its top.
 */
class SmbSource(
    override val id: String,
    override val displayName: String,
    private val host: String,
    private val share: String,
    private val username: String = "",
    private val password: String = "",
    private val domain: String = "",
    private val port: Int = DEFAULT_PORT,
    basePath: String = "/",
) : FileSource {
    override val rootPath: String = Paths.normalize(basePath)
    override val capabilities: Set<Capability> = Capability.values().toSet()

    // Android's crypto provider has no MD4, which NTLM logins need, so use
    // the Bouncy Castle provider smbj ships with on every platform.
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build(),
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null

    // -- reading --------------------------------------------------------

    override fun list(path: String): List<Entry> = withShare("open ${describe(path)}") { share ->
        val folder = Paths.normalize(path)
        share.list(smbPath(folder))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isFolder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                Entry(
                    sourceId = id,
                    path = Paths.join(folder, info.fileName),
                    name = info.fileName,
                    isFolder = isFolder,
                    size = if (isFolder) -1L else info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                    parent = folder,
                )
            }
    }

    override fun open(path: String): InputStream = withShare("open ${Paths.nameOf(path)}") { share ->
        val file = share.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        object : FilterInputStream(file.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
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
        var written = 0L
        withShare("save $name", nameOnConflict = name) { share ->
            share.openFile(
                smbPath(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                if (overwrite) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { file ->
                file.outputStream.use { output -> written = input.copyTo(output, BUFFER) }
            }
        }
        return Entry(id, path, name, false, written, System.currentTimeMillis(), mime, Paths.normalize(parentPath))
    }

    override fun mkdir(parentPath: String, name: String): Entry {
        val path = Paths.join(parentPath, name)
        withShare("create folder $name", nameOnConflict = name) { share -> share.mkdir(smbPath(path)) }
        return Entry(id, path, name, true, parent = Paths.normalize(parentPath))
    }

    override fun delete(entry: Entry) {
        if (Paths.normalize(entry.path) == "/") throw SourceException("will not delete the whole share")
        withShare("delete ${entry.name}") { share ->
            if (entry.isFolder) share.rmdir(smbPath(entry.path), true) else share.rm(smbPath(entry.path))
        }
    }

    override fun rename(entry: Entry, newName: String): Entry {
        val path = Paths.join(Paths.parentOf(entry.path), newName)
        withShare("rename ${entry.name}", nameOnConflict = newName) { share ->
            share.open(
                smbPath(entry.path),
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            ).use { it.rename(smbPath(path), false) }
        }
        return entry.copy(path = path, name = newName)
    }

    override fun describe(path: String): String {
        val tail = Paths.normalize(path).removePrefix("/")
        val portPart = if (port == DEFAULT_PORT) "" else ":$port"
        return "smb://$host$portPart/$share" + if (tail.isEmpty()) "" else "/$tail"
    }

    /** Connects and lists the starting folder; proves host, login and share in one go. */
    fun check() {
        list(rootPath)
    }

    @Synchronized
    override fun close() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client.close() }
        disk = null
        session = null
        connection = null
    }

    // -- plumbing -------------------------------------------------------

    private fun smbPath(path: String): String = Paths.normalize(path).removePrefix("/").replace('/', '\\')

    @Synchronized
    private fun connectedShare(): DiskShare {
        disk?.takeIf { it.isConnected }?.let { return it }
        runCatching { session?.close() }
        runCatching { connection?.close() }

        val newConnection = client.connect(host, port)
        val auth = if (username.isBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
        val newSession = newConnection.authenticate(auth)
        val newShare = newSession.connectShare(share) as? DiskShare
            ?: throw SourceException("\"$share\" on $host is not a file share")
        connection = newConnection
        session = newSession
        disk = newShare
        return newShare
    }

    /**
     * Runs [block] against the share, reconnecting once if the connection
     * went stale (a phone that slept, a NAS that dropped idle sessions).
     */
    private fun <T> withShare(what: String, nameOnConflict: String? = null, block: (DiskShare) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(connectedShare())
            } catch (e: SMBApiException) {
                throw translate(e, what, nameOnConflict)
            } catch (e: SourceException) {
                throw e
            } catch (e: Exception) {
                val transient = e is IOException || e is SMBRuntimeException
                if (transient && attempt == 0) {
                    attempt++
                    synchronized(this) {
                        runCatching { disk?.close() }
                        disk = null
                    }
                    continue
                }
                throw SourceException("could not reach $host: ${rootMessage(e)}", e)
            }
        }
    }

    private fun translate(e: SMBApiException, what: String, nameOnConflict: String?): SourceException =
        when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_COLLISION ->
                if (nameOnConflict != null) NameTakenException(nameOnConflict) else SourceException("could not $what: it already exists", e)
            NtStatus.STATUS_LOGON_FAILURE -> SourceException("$host did not accept the user name or password", e)
            NtStatus.STATUS_ACCESS_DENIED -> SourceException("could not $what: access denied", e)
            NtStatus.STATUS_BAD_NETWORK_NAME -> SourceException("$host has no share called \"$share\"", e)
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                SourceException("could not $what: not found", e)
            NtStatus.STATUS_DISK_FULL -> SourceException("could not $what: the share is full", e)
            NtStatus.STATUS_SHARING_VIOLATION -> SourceException("could not $what: it is open in another program", e)
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> SourceException("could not $what: the folder is not empty", e)
            else -> SourceException("could not $what (${e.status})", e)
        }

    private fun rootMessage(e: Throwable): String {
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }

    companion object {
        const val DEFAULT_PORT = 445
        private const val BUFFER = 256 * 1024
    }
}
