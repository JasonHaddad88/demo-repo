package com.vortex.explorer.data

import android.content.Context
import com.vortex.explorer.core.EntryKind
import com.vortex.explorer.core.ShuffleMode
import org.json.JSONArray
import org.json.JSONObject

/** Everything the user can tune, with defaults that favour a good shuffle. */
data class Preferences(
    val mode: ShuffleMode = ShuffleMode.FOLDER_SPREAD,
    val kinds: Set<EntryKind> = setOf(EntryKind.IMAGE, EntryKind.VIDEO),
    val includeSubfolders: Boolean = true,
    val noSameFolderTwice: Boolean = true,
    val bias: Float = 4f,
    val slideSeconds: Int = 6,
    val loopSlideshow: Boolean = true,
    val markSeen: Boolean = true,
    val showHidden: Boolean = false,
)

/** What is needed to rebuild the last shuffle: the seed does the rest. */
data class LastShuffle(
    val label: String,
    val origins: List<Pair<String, String>>,
    val kinds: Set<EntryKind>,
    val includeSubfolders: Boolean,
    val mode: ShuffleMode,
    val seed: String,
    val noSameFolderTwice: Boolean,
    val bias: Float,
    val position: Int,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Preferences {
        val json = prefs.getString(KEY_PREFS, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Preferences()
        val defaults = Preferences()
        return Preferences(
            mode = enumOr(json.optString("mode"), defaults.mode),
            kinds = kindsFrom(json.optJSONArray("kinds")) ?: defaults.kinds,
            includeSubfolders = json.optBoolean("subfolders", defaults.includeSubfolders),
            noSameFolderTwice = json.optBoolean("noRepeat", defaults.noSameFolderTwice),
            bias = json.optDouble("bias", defaults.bias.toDouble()).toFloat(),
            slideSeconds = json.optInt("slideSeconds", defaults.slideSeconds),
            loopSlideshow = json.optBoolean("loop", defaults.loopSlideshow),
            markSeen = json.optBoolean("markSeen", defaults.markSeen),
            showHidden = json.optBoolean("hidden", defaults.showHidden),
        )
    }

    fun save(value: Preferences) {
        val json = JSONObject()
            .put("mode", value.mode.name)
            .put("kinds", JSONArray(value.kinds.map { it.name }))
            .put("subfolders", value.includeSubfolders)
            .put("noRepeat", value.noSameFolderTwice)
            .put("bias", value.bias.toDouble())
            .put("slideSeconds", value.slideSeconds)
            .put("loop", value.loopSlideshow)
            .put("markSeen", value.markSeen)
            .put("hidden", value.showHidden)
        prefs.edit().putString(KEY_PREFS, json.toString()).apply()
    }

    fun lastShuffle(): LastShuffle? {
        val json = prefs.getString(KEY_LAST, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val origins = json.optJSONArray("origins") ?: return null
        return LastShuffle(
            label = json.optString("label"),
            origins = (0 until origins.length()).mapNotNull { index ->
                origins.optJSONObject(index)?.let { it.optString("source") to it.optString("path") }
            },
            kinds = kindsFrom(json.optJSONArray("kinds")) ?: Preferences().kinds,
            includeSubfolders = json.optBoolean("subfolders", true),
            mode = enumOr(json.optString("mode"), ShuffleMode.RANDOM),
            seed = json.optString("seed"),
            noSameFolderTwice = json.optBoolean("noRepeat", false),
            bias = json.optDouble("bias", 4.0).toFloat(),
            position = json.optInt("position", 0),
        ).takeIf { it.origins.isNotEmpty() && it.seed.isNotEmpty() }
    }

    fun saveLastShuffle(value: LastShuffle) {
        val origins = JSONArray()
        value.origins.forEach { (source, path) -> origins.put(JSONObject().put("source", source).put("path", path)) }
        val json = JSONObject()
            .put("label", value.label)
            .put("origins", origins)
            .put("kinds", JSONArray(value.kinds.map { it.name }))
            .put("subfolders", value.includeSubfolders)
            .put("mode", value.mode.name)
            .put("seed", value.seed)
            .put("noRepeat", value.noSameFolderTwice)
            .put("bias", value.bias.toDouble())
            .put("position", value.position)
        prefs.edit().putString(KEY_LAST, json.toString()).apply()
    }

    fun savePosition(position: Int) {
        lastShuffle()?.let { saveLastShuffle(it.copy(position = position)) }
    }

    private fun kindsFrom(array: JSONArray?): Set<EntryKind>? {
        array ?: return null
        val kinds = (0 until array.length()).mapNotNull { index ->
            runCatching { EntryKind.valueOf(array.optString(index)) }.getOrNull()
        }.toSet()
        return kinds.ifEmpty { null }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    private companion object {
        const val KEY_PREFS = "prefs"
        const val KEY_LAST = "last-shuffle"
    }
}
