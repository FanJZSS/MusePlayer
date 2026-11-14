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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            val title = queryDisplayName(data) ?: data.lastPathSegment ?: "视频"
            viewModel.start(listOf(PlayEntry(uri = data.toString(), title = title)), 0, 0L)
        }
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
    }
}
