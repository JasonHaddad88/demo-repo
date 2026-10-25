package com.vortex.view.data

import android.content.Context
import com.vortex.view.core.Folder
import org.json.JSONArray
import org.json.JSONObject

/** The folder you were sorting, the photo you were on, and where photos go. */
class Prefs(context: Context, name: String = "vortexview") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun clear() {
        prefs.edit().clear().commit()
    }

    var folder: Folder?
        get() {
            val id = prefs.getString("folder_id", null) ?: return null
            return Folder(id, prefs.getString("folder_name", null).orEmpty())
        }
        set(value) {
            prefs.edit().putString("folder_id", value?.id).putString("folder_name", value?.name).apply()
        }

    var lastPhoto: String?
        get() = prefs.getString("last_photo", null)
        set(value) = prefs.edit().putString("last_photo", value).apply()

    var targets: List<Folder>
        get() = runCatching {
            val array = JSONArray(prefs.getString("targets", "[]"))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Folder(item.getString("id"), item.optString("name"))
            }
        }.getOrDefault(emptyList())
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
            prefs.edit().putString("targets", array.toString()).apply()
        }
}
