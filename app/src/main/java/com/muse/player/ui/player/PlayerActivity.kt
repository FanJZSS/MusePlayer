package com.muse.player.ui.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.ui.theme.MuseTheme

class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MuseTheme(darkTheme = true) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                PlayerScreen(
                    viewModel = viewModel,
                    state = state,
                    onBack = { finish() }
                )
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
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
                val title = data.getQueryParameter("title")
                    ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "网络串流" }
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
            val url = extractMediaUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            if (url != null) {
                viewModel.start(
                    listOf(PlayEntry(uri = url, title = titleForUrl(url), isNetwork = true)),
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
            viewModel.start(buildEntries(listOf(data)), 0, 0L)
        }
    }

    private fun clipDataUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    private fun buildEntries(uris: List<Uri>): List<PlayEntry> = uris.map { uri ->
        val raw = queryDisplayName(uri) ?: uri.lastPathSegment ?: "视频"
        PlayEntry(
            uri = uri.toString(),
            title = raw.substringBeforeLast('.').ifBlank { raw },
            isNetwork = uri.scheme == "http" || uri.scheme == "https"
        )
    }

    /** 从分享文本中提取 m3u8 / mp4 等媒体链接 */
    private fun extractMediaUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return URL_REGEX.findAll(text)
            .map { it.value.trimEnd('。', '，', ',', '.', ')', '）', '!', '！', ';', '；') }
            .firstOrNull { url ->
                val path = url.substringBefore('?').lowercase()
                MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
            }
    }

    private fun titleForUrl(url: String): String =
        url.substringBefore('?')
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "网络串流" }

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
