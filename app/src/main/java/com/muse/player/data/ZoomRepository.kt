package com.muse.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 按视频地址保存画面缩放倍数，重启应用后仍能恢复。
 * 只记录用户手动缩放过的视频，未记录的使用默认 1x。
 */
object ZoomRepository {
    private const val PREFS = "muse_zoom"
    private const val KEY = "zoom_map"

    private lateinit var sp: SharedPreferences
    private var loaded = false
    private val map = HashMap<String, Float>()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(sp.getString(KEY, "{}") ?: "{}")
            json.keys().forEach { uri ->
                val value = json.optDouble(uri, Double.NaN)
                if (!value.isNaN()) map[uri] = value.toFloat()
            }
        }
    }

    fun get(uri: String): Float? = map[uri]

    fun put(uri: String, zoom: Float) {
        if (uri.isEmpty()) return
        if (map[uri] == zoom) return
        map[uri] = zoom
        persist()
    }

    private fun persist() {
        val json = JSONObject()
        map.forEach { (uri, zoom) -> json.put(uri, zoom.toDouble()) }
        sp.edit().putString(KEY, json.toString()).apply()
    }
}
