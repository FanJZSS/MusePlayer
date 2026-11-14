package com.muse.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放历史 / 播放进度，使用 SharedPreferences + JSON 持久化。
 */
object HistoryRepository {
    private const val PREFS = "muse_history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200

    private lateinit var sp: SharedPreferences
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _entries.value = read()
    }

    fun progressOf(uri: String): Long =
        _entries.value.firstOrNull { it.uri == uri }?.positionMs ?: 0L

    /**
     * 记录播放进度。播放到 95% 以上视为看完，进度清零。
     */
    fun record(uri: String, title: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank()) return
        val normalized = when {
            durationMs <= 0 -> positionMs.coerceAtLeast(0)
            positionMs >= durationMs * 0.95 -> 0L
            positionMs < 3_000 -> 0L
            else -> positionMs
        }
        val current = _entries.value.toMutableList()
        current.removeAll { it.uri == uri }
        current.add(
            0,
            HistoryEntry(
                uri = uri,
                title = title,
                positionMs = normalized,
                durationMs = durationMs,
                playedAt = System.currentTimeMillis()
            )
        )
        val trimmed = current.take(MAX_ENTRIES)
        _entries.value = trimmed
        persist(trimmed)
    }

    fun remove(uri: String) {
        val updated = _entries.value.filterNot { it.uri == uri }
        _entries.value = updated
        persist(updated)
    }

    fun clear() {
        _entries.value = emptyList()
        persist(emptyList())
    }

    private fun persist(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().apply {
                    put("uri", it.uri)
                    put("title", it.title)
                    put("position", it.positionMs)
                    put("duration", it.durationMs)
                    put("playedAt", it.playedAt)
                }
            )
        }
        sp.edit().putString(KEY, array.toString()).apply()
    }

    private fun read(): List<HistoryEntry> {
        val raw = sp.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                HistoryEntry(
                    uri = obj.optString("uri"),
                    title = obj.optString("title"),
                    positionMs = obj.optLong("position"),
                    durationMs = obj.optLong("duration"),
                    playedAt = obj.optLong("playedAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
