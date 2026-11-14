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
    val autoSubtitles: Boolean = true,   // 自动加载同名字幕
    val showHiddenFolders: Boolean = false, // 是否显示已隐藏文件夹
    val hiddenFolders: Set<String> = emptySet(), // 被隐藏的文件夹 bucketId
    val showSystemHiddenFolders: Boolean = false // 是否扫描并显示以 . 开头的系统隐藏文件夹
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
            autoSubtitles = sp.getBoolean("auto_subtitles", true),
            showHiddenFolders = sp.getBoolean("show_hidden_folders", false),
            hiddenFolders = sp.getStringSet("hidden_folders", emptySet())?.toSet() ?: emptySet(),
            showSystemHiddenFolders = sp.getBoolean("show_system_hidden_folders", false)
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

    fun setShowHiddenFolders(value: Boolean) {
        sp.edit().putBoolean("show_hidden_folders", value).apply()
        _state.value = _state.value.copy(showHiddenFolders = value)
    }

    fun setFolderHidden(bucketId: String, hidden: Boolean) {
        val current = _state.value.hiddenFolders
        val updated = if (hidden) current + bucketId else current - bucketId
        if (updated == current) return
        sp.edit().putStringSet("hidden_folders", updated).apply()
        _state.value = _state.value.copy(hiddenFolders = updated)
    }

    fun toggleFolderHidden(bucketId: String) {
        setFolderHidden(bucketId, bucketId !in _state.value.hiddenFolders)
    }

    fun setShowSystemHiddenFolders(value: Boolean) {
        sp.edit().putBoolean("show_system_hidden_folders", value).apply()
        _state.value = _state.value.copy(showSystemHiddenFolders = value)
    }
}
