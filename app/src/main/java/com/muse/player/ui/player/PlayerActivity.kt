package com.muse.player.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.ui.theme.MuseTheme
import com.muse.player.util.NetworkTitles

class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 播放器始终是深色背景，强制状态栏/导航栏使用浅色图标，避免系统跟随日间模式变黑看不清
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 关闭系统给手势条自动加的浅色描边背景
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // 先连接播放服务并提交播放请求，与 Compose 初始化并行执行，缩短起播等待
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            MuseTheme(darkTheme = true) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                PlayerScreen(
                    viewModel = viewModel,
                    state = state,
                    // 注意：不能再调用 onBackPressedDispatcher.onBackPressed()，
                    // 否则会与 PlayerScreen 内的 BackHandler 形成无限递归
                    onBack = {
                        viewModel.pauseAndSave()
                        finish()
                    }
                )
            }
        }

        // 返回逻辑统一交给 PlayerScreen 内的 BackHandler 处理（暂停 + finish）。
        // 这里不再额外注册 Activity 级返回回调，避免两个回调互相抢占导致需要多次返回才退出。
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        val entries = intent.getParcelableArrayListExtra<PlayEntry>(EXTRA_ENTRIES)
        if (!entries.isNullOrEmpty()) {
            viewModel.start(
                entries = entries,
                index = intent.getIntExtra(EXTRA_INDEX, 0),
                positionMs = intent.getLongExtra(EXTRA_POSITION, 0L)
            )
            return
        }

        // 其他应用通过 muse://play?url=... 调起
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && data.scheme == SCHEME_MUSE) {
            val url = data.getQueryParameter("url") ?: data.getQueryParameter("src")
            if (!url.isNullOrBlank()) {
                val title = data.getQueryParameter("title") ?: NetworkTitles.of(url)
                viewModel.start(
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
            }
            return
        }

        // 其他应用通过“分享”或“打开方式”传入的视频
        val streamUris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )

            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ) ?: emptyList()

            else -> emptyList()
        }
        val sharedUris = (streamUris + clipDataUris(intent)).distinctBy { it.toString() }
        if (sharedUris.isNotEmpty()) {
            viewModel.start(buildEntries(sharedUris), 0, 0L)
            return
        }

        // 其他应用分享文本中的网络流媒体链接（如 m3u8 地址）
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val url = extractMediaUrl(text)
            if (url != null) {
                // 优先使用调用方给的标题，其次是分享文本里链接前的视频名称
                val title = intentTitle(intent)
                    ?: titleFromShareText(text, url)
                    ?: NetworkTitles.of(url)
                viewModel.start(
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
            } else {
                Toast.makeText(this, "未找到可播放的视频链接", Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }

        if (intent.action == Intent.ACTION_VIEW && data != null) {
            viewModel.start(buildEntries(listOf(data), intentTitle(intent)), 0, 0L)
        }
    }

    private fun clipDataUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun buildEntries(uris: List<Uri>, preferredTitle: String? = null): List<PlayEntry> =
        uris.map { uri ->
            val network = uri.scheme == "http" || uri.scheme == "https"
            val raw = queryDisplayName(uri) ?: uri.lastPathSegment ?: "视频"
            val localTitle = raw.substringBeforeLast('.').ifBlank { raw }
            PlayEntry(
                uri = uri.toString(),
                title = if (network) {
                    preferredTitle?.takeIf { it.isNotBlank() } ?: NetworkTitles.of(uri.toString())
                } else {
                    localTitle
                },
                isNetwork = network
            )
        }

    /** 从分享文本中提取网络地址：优先视频扩展名，否则取第一个 http(s) 链接 */
    private fun extractMediaUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val candidates = URL_REGEX.findAll(text)
            .map { it.value.trimEnd('。', '，', ',', '.', ')', '）', '!', '！', ';', '；') }
            .toList()
        return candidates.firstOrNull { url ->
            val path = url.substringBefore('?').lowercase()
            MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
        } ?: candidates.firstOrNull()
    }

    /** 其他应用可能通过 EXTRA_TITLE / EXTRA_SUBJECT / ClipData 标签传入视频名称 */
    private fun intentTitle(intent: Intent): String? =
        listOf(
            intent.getStringExtra(Intent.EXTRA_TITLE),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
            intent.clipData?.description?.label?.toString()
        ).mapNotNull { it?.trim() }
            .firstOrNull { it.length in 2..80 && !it.contains('\n') && !NetworkTitles.isPlaceholder(it) }

    /** 分享文本常在链接前带上视频名称，例如“《庆余年》第 1 集 https://.../index.m3u8” */
    private fun titleFromShareText(text: String?, url: String): String? {
        if (text.isNullOrBlank()) return null
        val index = text.indexOf(url)
        if (index <= 0) return null
        val title = text.substring(0, index)
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(我正在看|我在看|正在播放|分享视频|分享|推荐)[:：]?\\s*"), "")
            .trim()
            .trim('“', '”', '"', '「', '」', '【', '】', '《', '》', '：', ':', '-', '—', '|', '·', ' ')
        return title.takeIf { it.length in 2..80 }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val size = viewModel.state.value
        val ratio = if (size.videoWidth > 0 && size.videoHeight > 0) {
            Rational(size.videoWidth, size.videoHeight)
        } else {
            Rational(16, 9)
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .build()
        try {
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) return
        if (!SettingsRepository.state.value.backgroundPlay) {
            // 在 onPause 就暂停，比等到 onStop 更早，避免退出时还有一两秒声音
            viewModel.pauseAndSave()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        if (SettingsRepository.state.value.backgroundPlay) {
            viewModel.saveProgress()
        } else {
            viewModel.pauseAndSave()
        }
    }

    companion object {
        const val EXTRA_ENTRIES = "extra_entries"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_POSITION = "extra_position"
        private const val SCHEME_MUSE = "muse"
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+")
        private val MEDIA_EXTENSIONS = listOf(
            "m3u8", "m3u", "mpd", "mp4", "mkv", "webm", "flv", "ts", "mov", "avi", "m4v", "3gp"
        )
    }
}
