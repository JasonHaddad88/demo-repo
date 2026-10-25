package com.vortex.explorer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A place VortexPlorer can browse, as saved between launches. */
sealed class SourceSpec {
    abstract val id: String
    abstract val name: String

    /** The phone's photos and videos, via MediaStore. Always present. */
    data object Gallery : SourceSpec() {
        override val id = GALLERY_ID
        override val name = "Photos & videos"
    }

    /** A folder picked with the system picker: phone storage, SD card or USB drive. */
    data class Folder(override val id: String, override val name: String, val treeUri: String) : SourceSpec()

    data class Smb(
        override val id: String,
        override val name: String,
        val host: String,
        val port: Int,
        val share: String,
        val path: String,
        val user: String,
        val domain: String,
    ) : SourceSpec()

    data class WebDav(
        override val id: String,
        override val name: String,
        val url: String,
        val user: String,
    ) : SourceSpec()

    val isNetwork: Boolean get() = this is Smb || this is WebDav

    companion object {
        const val GALLERY_ID = "gallery"
        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}

/**
 * Saved sources, in SharedPreferences as JSON. Passwords never touch that
 * file in the clear: they are sealed with a key that lives in the Android
 * Keystore and cannot leave the device.
 */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)
    private val vault = Vault()

    fun all(): List<SourceSpec> {
        val saved = runCatching { JSONArray(prefs.getString(KEY_SOURCES, "[]")) }.getOrDefault(JSONArray())
        val specs = (0 until saved.length()).mapNotNull { decode(saved.optJSONObject(it)) }
        return listOf(SourceSpec.Gallery) + specs
    }

    fun find(id: String): SourceSpec? = all().firstOrNull { it.id == id }

    fun save(spec: SourceSpec, password: String? = null) {
        if (spec is SourceSpec.Gallery) return
        val others = all().filter { it.id != spec.id && it !is SourceSpec.Gallery }
        write(others + spec)
        if (password != null) {
            prefs.edit().putString(KEY_SECRET + spec.id, vault.seal(password)).apply()
        }
    }

    fun remove(id: String) {
        write(all().filter { it.id != id && it !is SourceSpec.Gallery })
        prefs.edit().remove(KEY_SECRET + id).apply()
    }

    fun password(id: String): String =
        prefs.getString(KEY_SECRET + id, null)?.let(vault::open).orEmpty()

    private fun write(specs: List<SourceSpec>) {
        val array = JSONArray()
        specs.mapNotNull(::encode).forEach { array.put(it) }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private fun encode(spec: SourceSpec): JSONObject? = when (spec) {
        is SourceSpec.Gallery -> null
        is SourceSpec.Folder -> JSONObject()
            .put("type", "folder").put("id", spec.id).put("name", spec.name).put("tree", spec.treeUri)
        is SourceSpec.Smb -> JSONObject()
            .put("type", "smb").put("id", spec.id).put("name", spec.name).put("host", spec.host)
            .put("port", spec.port).put("share", spec.share).put("path", spec.path)
            .put("user", spec.user).put("domain", spec.domain)
        is SourceSpec.WebDav -> JSONObject()
            .put("type", "webdav").put("id", spec.id).put("name", spec.name).put("url", spec.url)
            .put("user", spec.user)
    }

    private fun decode(json: JSONObject?): SourceSpec? {
        json ?: return null
        val id = json.optString("id").ifEmpty { return null }
        val name = json.optString("name")
        return when (json.optString("type")) {
            "folder" -> SourceSpec.Folder(id, name, json.optString("tree"))
            "smb" -> SourceSpec.Smb(
                id, name, json.optString("host"), json.optInt("port", 445), json.optString("share"),
                json.optString("path", "/"), json.optString("user"), json.optString("domain"),
            )
            "webdav" -> SourceSpec.WebDav(id, name, json.optString("url"), json.optString("user"))
            else -> null
        }
    }

    private companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_SECRET = "secret."
    }
}

/** AES-GCM with a non-exportable key held by the Android Keystore. */
private class Vault {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "vortexplorer.sources"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
