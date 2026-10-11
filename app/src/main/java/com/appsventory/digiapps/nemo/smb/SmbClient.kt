package com.appsventory.digiapps.nemo.smb

import android.content.Context
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** One entry of an SMB directory. */
class SmbEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

/**
 * Thin wrapper over jcifs-ng. Blocking calls — run them on Dispatchers.IO.
 * Paths look like smb://host/share/folder/file ; the credentials come from [SmbStore].
 */
object SmbClient {
    private val contexts = ConcurrentHashMap<String, CIFSContext>()
    private lateinit var appContext: Context

    /** Called once at app start (see MainActivity). */
    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private fun ensureNetwork() {
        if (::appContext.isInitialized) SmbNetwork.ensureWifiBound(appContext)
    }

    private val config by lazy {
        PropertyConfiguration(
            Properties().apply {
                setProperty("jcifs.smb.client.connTimeout", "8000")
                setProperty("jcifs.smb.client.responseTimeout", "15000")
                setProperty("jcifs.smb.client.soTimeout", "20000")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.tcpNoDelay", "true")
                // IMPORTANT: do NOT hard-disable SMB1 here. jcifs-ng's "browse the server"
                // share enumeration (listing smb://host/) uses a legacy RAP call that only
                // works when SMB1 negotiation is allowed - actual file access still upgrades
                // to SMB2/3 automatically when the server supports it (CasaOS/Samba do).
                // Forcing SMB2-only breaks auto-detected shares even though everything else
                // (login, browsing inside a share) keeps working - that was this app's bug.
                setProperty("jcifs.smb.client.minVersion", "SMB1")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.signingPreferred", "false")
                setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
                // We always connect by IP directly - skip NetBIOS broadcast/WINS lookups
                setProperty("jcifs.resolveOrder", "DNS")
            }
        )
    }

    private fun base(): CIFSContext = BaseContext(config)

    private fun contextFor(path: String): CIFSContext {
        ensureNetwork()
        val key = smbHostOf(path)
        return contexts.getOrPut(key) {
            val saved = SmbStore.find(key)
            if (saved == null || saved.anonymous) base().withGuestCrendentials()
            else {
                val given = saved.domain.ifBlank { null }
                base().withCredentials(NtlmPasswordAuthenticator(given, saved.username, saved.password))
            }
        }
    }

    /** Forget cached sessions for a host (after editing or removing its connection). */
    fun invalidate(hostKey: String) {
        contexts.remove(hostKey.lowercase())?.let { runCatching { it.close() } }
    }

    /**
     * Try to open the server root with [connection]'s credentials (anonymous tries guest, then null session).
     * On success the session is kept for later browsing. Throws on failure.
     */
    fun connect(connection: SmbConnection) {
        ensureNetwork()
        val candidates = if (connection.anonymous) {
            listOf(base().withGuestCrendentials(), base().withAnonymousCredentials())
        } else {
            val given = connection.domain.ifBlank { null }
            val domains: List<String?> = listOf(given, null, "WORKGROUP").distinct()
            domains.map { d ->
                base().withCredentials(NtlmPasswordAuthenticator(d, connection.username, connection.password))
            }
        }
        var failure: Exception? = null
        for (context in candidates) {
            try {
                val f = SmbFile(connection.root, context)
                runCatching { f.listFiles() }.getOrElse { err ->
                    if (!f.exists()) throw err
                }
                contexts.remove(connection.id)
                contexts[connection.id] = context
                return
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IOException("Unable to connect")
    }

    fun isAuthError(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SmbAuthException }

    private fun dirFile(path: String) = SmbFile(path.trimEnd('/') + "/", contextFor(path))
    private fun file(path: String) = SmbFile(path, contextFor(path))

    fun join(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    fun parent(path: String): String? {
        val rest = path.removePrefix("smb://").trimEnd('/')
        if (!rest.contains('/')) return null // server root has no parent
        return "smb://" + rest.substringBeforeLast('/') + "/"
    }

    /**
     * Server root: shares. Automatic enumeration (admin shares like IPC$/C$ hidden) is tried
     * first and merged with any hand-added bookmarks (Menu > Add shared folder) for servers
     * that refuse anonymous/guest share listing - a common restriction on Windows and some
     * NAS/Samba setups even when the shares themselves are perfectly reachable.
     * Anywhere else: a normal folder listing.
     */
    fun list(path: String): List<SmbEntry> {
        val atServerRoot = !path.removePrefix("smb://").trimEnd('/').contains('/')
        if (atServerRoot) {
            val auto = runCatching { dirFile(path).listFiles().orEmpty().toList() }.getOrDefault(emptyList())
                .map { it.name.trimEnd('/') }
                .filterNot { it.endsWith("$") }
            val manual = SmbStore.find(smbHostOf(path))?.shares.orEmpty()
            return (auto + manual)
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
                .map { name -> SmbEntry(join(path, name), name, true, 0L, 0L) }
        }
        val files = dirFile(path).listFiles().orEmpty()
        return files.map { f ->
            val name = f.name.trimEnd('/')
            val isDir = runCatching { f.isDirectory }.getOrDefault(false)
            val size = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L)
            val modified = runCatching { f.lastModified() }.getOrDefault(0L)
            SmbEntry(join(path, name), name, isDir, size, modified)
        }
    }

    fun stat(path: String): SmbEntry? = runCatching {
        val f = file(path)
        if (!f.exists()) null
        else {
            val isDir = f.isDirectory
            SmbEntry(path.trimEnd('/'), f.name.trimEnd('/'), isDir, if (isDir) 0L else f.length(), f.lastModified())
        }
    }.getOrNull()

    fun exists(path: String): Boolean = runCatching { file(path).exists() }.getOrDefault(false)

    fun input(path: String): InputStream = file(path).inputStream

    fun output(path: String): OutputStream = file(path).outputStream

    fun mkdir(path: String): Boolean = runCatching { dirFile(path).mkdir(); true }.getOrDefault(false)

    fun delete(path: String): Boolean = runCatching {
        val f = file(path)
        if (f.isDirectory) dirFile(path).delete() else f.delete()
        true
    }.getOrDefault(false)

    /** Rename / move inside the same server. */
    fun renameTo(path: String, newPath: String): Boolean = runCatching {
        val source = if (stat(path)?.isDirectory == true) dirFile(path) else file(path)
        val target = if (stat(path)?.isDirectory == true) dirFile(newPath) else file(newPath)
        source.renameTo(target)
        true
    }.getOrDefault(false)

    /** Download an SMB file into the app cache so other apps can open it. */
    fun downloadToCache(context: Context, path: String): File {
        val dir = File(context.cacheDir, "smb").apply { mkdirs() }
        val target = File(dir, path.substringAfterLast('/'))
        input(path).use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        return target
    }
}
