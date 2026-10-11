package com.appsventory.digiapps.nemo.smb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One saved SMB server. One connection = one server: the root is `smb://host/` and every
 * share on that server shows up as a folder inside it, the way Finder/Explorer browse a server.
 * [shares] are share names added by hand (Menu > Add shared folder) for servers that don't
 * answer automatic share enumeration — merged with whatever browsing the root finds live.
 */
data class SmbConnection(
    val host: String,
    val name: String = "",
    val port: Int = 445,
    val anonymous: Boolean = true,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val shares: List<String> = emptyList()
) {
    /** Lower-case host, used as the key everywhere. */
    val id: String get() = host.lowercase()
    val title: String get() = name.ifBlank { host }
    val root: String get() = if (port == 445) "smb://$host/" else "smb://$host:$port/"
}

/** Host part of an smb:// path, without port ("smb://Nas:445/a/b" -> "nas"). */
fun smbHostOf(path: String): String =
    path.removePrefix("smb://").substringBefore('/').substringBefore(':').lowercase()

fun isSmbPath(path: String): Boolean = path.startsWith("smb://")

/** Saved connections (Compose state). Passwords are encrypted with an Android Keystore key. */
object SmbStore {
    private lateinit var prefs: SharedPreferences

    var connections by mutableStateOf<List<SmbConnection>>(emptyList())
        private set

    fun ensureLoaded(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("digifiles_smb", Context.MODE_PRIVATE)
        connections = runCatching {
            val array = JSONArray(prefs.getString("connections", "[]"))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SmbConnection(
                    host = o.getString("host"),
                    name = o.optString("name"),
                    port = o.optInt("port", 445),
                    anonymous = o.optBoolean("anonymous", true),
                    username = o.optString("username"),
                    password = SecretBox.decrypt(o.optString("password")),
                    domain = o.optString("domain"),
                    shares = o.optJSONArray("shares")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    }.orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun find(hostKey: String): SmbConnection? = connections.firstOrNull { it.id == hostKey.lowercase() }

    /** Add a hand-typed share name to a saved server (no-op if unknown host, blank, or already there). */
    fun addShare(hostKey: String, share: String) {
        val name = share.trim().trim('/')
        val existing = find(hostKey) ?: return
        if (name.isEmpty() || existing.shares.any { it.equals(name, ignoreCase = true) }) return
        upsert(existing.copy(shares = existing.shares + name))
    }

    /** Remove a hand-typed share bookmark (does not touch anything on the server). */
    fun removeShare(hostKey: String, share: String) {
        val existing = find(hostKey) ?: return
        upsert(existing.copy(shares = existing.shares.filterNot { it.equals(share, ignoreCase = true) }))
    }

    fun upsert(connection: SmbConnection) {
        connections = connections.filter { it.id != connection.id } + connection
        persist()
    }

    fun remove(hostKey: String) {
        connections = connections.filter { it.id != hostKey.lowercase() }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        connections.forEach { c ->
            array.put(
                JSONObject()
                    .put("host", c.host).put("name", c.name).put("port", c.port)
                    .put("anonymous", c.anonymous).put("username", c.username)
                    .put("password", SecretBox.encrypt(c.password)).put("domain", c.domain)
                    .put("shares", JSONArray(c.shares))
            )
        }
        prefs.edit().putString("connections", array.toString()).apply()
    }
}

/** AES-GCM with a non-exportable key in the Android Keystore. */
private object SecretBox {
    private const val ALIAS = "digifiles_smb_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = cipher.iv + cipher.doFinal(plain.toByteArray())
            Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            String(cipher.doFinal(raw, 12, raw.size - 12))
        }.getOrDefault("")
    }
}
