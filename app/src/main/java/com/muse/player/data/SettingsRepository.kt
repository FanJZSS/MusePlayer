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
    val showSystemHiddenFolders: Boolean = false, // 是否扫描并显示以 . 开头的系统隐藏文件夹
    val folderViewMode: Boolean = false, // 媒体库默认展示文件夹视图
    val gridViewMode: Boolean = false, // 视频以列表展示（true 为网格）
    val sortField: String = "DATE", // 媒体库排序字段（SortField 名称）
    val sortAscending: Boolean = false, // 媒体库排序是否升序
    val seekGestureSeconds: Int = 300, // 全屏宽度左右滑动可调节的时长（秒）
    val longPressSpeed: Float = 2f, // 长按画面临时倍速播放的倍率（最低 2x）
    val doubleTapSeekSeconds: Int = 10, // 双击画面两侧快进/快退的秒数
    val showMediaNotification: Boolean = false, // 通知栏媒体播放卡片（默认隐藏）
    val customAspectWidth: Int = 16, // 自定义画面比例的宽
    val customAspectHeight: Int = 9, // 自定义画面比例的高
    val decoderMode: Int = 0, // 0 自动（硬解优先+软解回退）, 1 强制软件解码, 2 强制硬件解码
    val repeatMode: Int = 0, // 0 顺序播放, 1 单曲循环, 2 列表循环
    val shuffleEnabled: Boolean = false, // 随机播放
    val lowPerformanceMode: Boolean = false, // 低性能模式：不加载视频缩略图
    // 底部导航栏（窄屏底栏 / 宽屏侧栏）中显示的分栏，按显示顺序保存
    val bottomNavTabs: List<Int> = listOf(
        NavTabs.LIBRARY,
        NavTabs.HISTORY,
        NavTabs.BROWSE,
        NavTabs.NETWORK
    )
)

/**
 * 分栏索引：0 媒体库, 1 历史, 2 浏览, 3 网络。
 * 侧栏的「设置」入口不属于分栏，单独处理。
 */
object NavTabs {
    const val LIBRARY = 0
    const val HISTORY = 1
    const val BROWSE = 2
    const val NETWORK = 3

    /** 所有分栏，索引即分栏序号 */
    val ALL: List<Int> = listOf(LIBRARY, HISTORY, BROWSE, NETWORK)

    fun title(tab: Int): String = when (tab) {
        LIBRARY -> "媒体库"
        HISTORY -> "历史"
        BROWSE -> "浏览"
        NETWORK -> "网络"
        else -> ""
    }

    /** 过滤掉未知索引、去重并按 [ALL] 的顺序规范化 */
    fun normalize(tabs: Collection<Int>): List<Int> =
        ALL.filter { it in tabs }
}

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
            showSystemHiddenFolders = sp.getBoolean("show_system_hidden_folders", false),
            folderViewMode = sp.getBoolean("folder_view_mode", false),
            gridViewMode = sp.getBoolean("grid_view_mode", false),
            sortField = sp.getString("sort_field", "DATE") ?: "DATE",
            sortAscending = sp.getBoolean("sort_ascending", false),
            seekGestureSeconds = sp.getInt("seek_gesture_seconds", 300),
            longPressSpeed = sp.getFloat("long_press_speed", 2f),
            doubleTapSeekSeconds = sp.getInt("double_tap_seek_seconds", 10),
            showMediaNotification = sp.getBoolean("media_notification", false),
            customAspectWidth = sp.getInt("custom_aspect_width", 16),
            customAspectHeight = sp.getInt("custom_aspect_height", 9),
            decoderMode = sp.getInt("decoder_mode", 0),
            repeatMode = sp.getInt("repeat_mode", 0),
            shuffleEnabled = sp.getBoolean("shuffle_enabled", false),
            lowPerformanceMode = sp.getBoolean("low_performance_mode", false),
            bottomNavTabs = readBottomNavTabs()
        )
    }

    private fun readBottomNavTabs(): List<Int> {
        // 未配置过（首次启动）时沿用默认的全部四个分栏
        if (!sp.contains("bottom_nav_tabs")) {
            return NavTabs.ALL
        }
        val saved = sp.getStringSet("bottom_nav_tabs", emptySet()) ?: emptySet()
        val tabs = NavTabs.normalize(saved.mapNotNull { it.toIntOrNull() })
        // 配置异常（全部失效）时回退到默认，避免导航栏彻底消失且无处可改
        return tabs.ifEmpty { NavTabs.ALL }
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

    fun setFolderViewMode(value: Boolean) {
        sp.edit().putBoolean("folder_view_mode", value).apply()
        _state.value = _state.value.copy(folderViewMode = value)
    }

    fun setGridViewMode(value: Boolean) {
        sp.edit().putBoolean("grid_view_mode", value).apply()
        _state.value = _state.value.copy(gridViewMode = value)
    }

    fun setSort(field: String, ascending: Boolean) {
        sp.edit()
            .putString("sort_field", field)
            .putBoolean("sort_ascending", ascending)
            .apply()
        _state.value = _state.value.copy(sortField = field, sortAscending = ascending)
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

    fun setSeekGestureSeconds(value: Int) {
        val safe = value.coerceIn(60, 3600)
        sp.edit().putInt("seek_gesture_seconds", safe).apply()
        _state.value = _state.value.copy(seekGestureSeconds = safe)
    }

    fun setLongPressSpeed(value: Float) {
        // 支持 2x 到 8x，以 0.5x 为步进，最低 2x
        val safe = (kotlin.math.round(value * 2f) / 2f).coerceIn(2f, 8f)
        sp.edit().putFloat("long_press_speed", safe).apply()
        _state.value = _state.value.copy(longPressSpeed = safe)
    }

    fun setDoubleTapSeekSeconds(value: Int) {
        val safe = value.coerceIn(5, 60)
        sp.edit().putInt("double_tap_seek_seconds", safe).apply()
        _state.value = _state.value.copy(doubleTapSeekSeconds = safe)
    }

    fun setShowMediaNotification(value: Boolean) {
        sp.edit().putBoolean("media_notification", value).apply()
        _state.value = _state.value.copy(showMediaNotification = value)
    }

    fun setCustomAspectRatio(width: Int, height: Int) {
        val w = width.coerceIn(1, 999)
        val h = height.coerceIn(1, 999)
        sp.edit()
            .putInt("custom_aspect_width", w)
            .putInt("custom_aspect_height", h)
            .apply()
        _state.value = _state.value.copy(customAspectWidth = w, customAspectHeight = h)
    }

    fun setDecoderMode(value: Int) {
        sp.edit().putInt("decoder_mode", value).apply()
        _state.value = _state.value.copy(decoderMode = value)
    }

    fun setRepeatMode(value: Int) {
        sp.edit().putInt("repeat_mode", value).apply()
        _state.value = _state.value.copy(repeatMode = value)
    }

    fun setShuffleEnabled(value: Boolean) {
        sp.edit().putBoolean("shuffle_enabled", value).apply()
        _state.value = _state.value.copy(shuffleEnabled = value)
    }

    fun setLowPerformanceMode(value: Boolean) {
        sp.edit().putBoolean("low_performance_mode", value).apply()
        _state.value = _state.value.copy(lowPerformanceMode = value)
    }

    /**
     * 设置导航栏中显示的分栏。
     * 至少保留一个分栏，否则底栏/侧栏会连自身入口一起消失，用户再也无法从界面恢复。
     */
    fun setBottomNavTabVisible(tab: Int, visible: Boolean) {
        if (tab !in NavTabs.ALL) return
        val current = _state.value.bottomNavTabs
        if ((tab in current) == visible) return

        val updated = NavTabs.normalize(if (visible) current + tab else current - tab)
        if (updated.isEmpty()) return

        sp.edit()
            .putStringSet("bottom_nav_tabs", updated.map { it.toString() }.toSet())
            .apply()
        _state.value = _state.value.copy(bottomNavTabs = updated)
    }

    fun resetBottomNavTabs() {
        val defaults = NavTabs.ALL
        sp.edit()
            .putStringSet("bottom_nav_tabs", defaults.map { it.toString() }.toSet())
            .apply()
        _state.value = _state.value.copy(bottomNavTabs = defaults)
    }
}

