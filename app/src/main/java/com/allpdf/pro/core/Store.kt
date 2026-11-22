package com.allpdf.pro.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(val path: String, val name: String, val size: Long, val tool: String, val time: Long, val location: String)
data class RecentItem(val uri: String, val name: String, val time: Long)

/** Local persistence (SharedPreferences + JSON). Exposes Compose state for live UI updates. */
object Store {
    private lateinit var prefs: SharedPreferences

    val history = mutableStateListOf<HistoryItem>()
    val recents = mutableStateListOf<RecentItem>()
    val favorites = mutableStateListOf<String>()
    val darkMode = mutableStateOf(0) // 0 system, 1 light, 2 dark

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("allpdf", Context.MODE_PRIVATE)
        darkMode.value = prefs.getInt("dark", 0)
        favorites.clear(); favorites.addAll(prefs.getString("favs", "")!!.split(',').filter { it.isNotBlank() })
        history.clear()
        try {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val h = HistoryItem(o.getString("p"), o.getString("n"), o.optLong("s"), o.optString("t"), o.optLong("ts"), o.optString("l"))
                if (File(h.path).exists()) history.add(h)
            }
        } catch (_: Exception) {}
        recents.clear()
        try {
            val a = JSONArray(prefs.getString("recents", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                recents.add(RecentItem(o.getString("u"), o.getString("n"), o.optLong("ts")))
            }
        } catch (_: Exception) {}
    }

    fun setDark(v: Int) { darkMode.value = v; prefs.edit().putInt("dark", v).apply() }

    fun toggleFavorite(id: String) {
        if (favorites.contains(id)) favorites.remove(id) else favorites.add(0, id)
        prefs.edit().putString("favs", favorites.joinToString(",")).apply()
    }

    fun addHistory(items: List<HistoryItem>) {
        history.addAll(0, items)
        while (history.size > 300) history.removeAt(history.size - 1)
        saveHistory()
    }

    fun removeHistory(item: HistoryItem) {
        history.remove(item)
        File(item.path).delete()
        saveHistory()
    }

    private fun saveHistory() {
        val a = JSONArray()
        history.forEach { a.put(JSONObject().put("p", it.path).put("n", it.name).put("s", it.size).put("t", it.tool).put("ts", it.time).put("l", it.location)) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    fun addRecent(uri: String, name: String) {
        recents.removeAll { it.uri == uri }
        recents.add(0, RecentItem(uri, name, System.currentTimeMillis()))
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        val a = JSONArray()
        recents.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name).put("ts", it.time)) }
        prefs.edit().putString("recents", a.toString()).apply()
    }

    fun clearRecents() { recents.clear(); prefs.edit().putString("recents", "[]").apply() }

    // Generic JSON blobs (workflows etc.)
    fun getJson(key: String, def: String): String = prefs.getString(key, def) ?: def
    fun putJson(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    /** Moves engine outputs into permanent app storage, exports to Downloads and records history. */
    fun publish(ctx: Context, outputs: List<File>, tool: String): List<HistoryItem> {
        val items = outputs.map { f ->
            val dest = Files.unique(Env.outputsDir, f.name)
            if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
            val loc = Files.exportToDownloads(ctx, dest)
            HistoryItem(dest.absolutePath, dest.name, dest.length(), tool, System.currentTimeMillis(), loc)
        }
        addHistory(items)
        return items
    }
}
