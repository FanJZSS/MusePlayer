package com.muse.player.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val themeMode: Int = 0,          // 0 跟随系统, 1 浅色, 2 深色
    val autoResume: Boolean = true,  // 续播
    val backgroundPlay: Boolean = false, // 离开播放器后继续播放
    val autoSubtitles: Boolean = true    // 自动加载同名字幕
)

object SettingsRepository {
    private const val PREFS = "muse_settings"

    private lateinit var sp: SharedPreferences
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = AppSettings(
            themeMode = sp.getInt("theme_mode", 0),
            autoResume = sp.getBoolean("auto_resume", true),
            backgroundPlay = sp.getBoolean("background_play", false),
            autoSubtitles = sp.getBoolean("auto_subtitles", true)
        )
    }

    fun setThemeMode(value: Int) {
        sp.edit().putInt("theme_mode", value).apply()
        _state.value = _state.value.copy(themeMode = value)
    }

    fun setAutoResume(value: Boolean) {
        sp.edit().putBoolean("auto_resume", value).apply()
        _state.value = _state.value.copy(autoResume = value)
    }

    fun setBackgroundPlay(value: Boolean) {
        sp.edit().putBoolean("background_play", value).apply()
        _state.value = _state.value.copy(backgroundPlay = value)
    }

    fun setAutoSubtitles(value: Boolean) {
        sp.edit().putBoolean("auto_subtitles", value).apply()
        _state.value = _state.value.copy(autoSubtitles = value)
    }
}
