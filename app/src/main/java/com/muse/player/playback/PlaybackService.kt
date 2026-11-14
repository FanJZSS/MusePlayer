package com.muse.player.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.muse.player.data.SettingsRepository
import com.muse.player.playback.network.MuseDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 后台播放服务：MediaSessionService + ExoPlayer，
 * 支持通知栏控制、后台播放、画中画切换、硬件/软件解码切换。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var appliedDecoderMode = SettingsRepository.state.value.decoderMode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = buildPlayer()
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        // 解码模式变化时重建播放器，保留当前播放队列与进度
        scope.launch {
            SettingsRepository.state
                .map { it.decoderMode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode != appliedDecoderMode) {
                        appliedDecoderMode = mode
                        rebuildPlayer()
                    }
                }
        }
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(MuseDataSource.Factory(this))
        )
        .setRenderersFactory(buildRenderersFactory())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val mode = SettingsRepository.state.value.decoderMode
        // 自动模式：优先硬件解码，失败时自动回退到软件解码
        val factory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(mode == DECODER_AUTO)
        if (mode == DECODER_AUTO) return factory

        return factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (mode == DECODER_SOFTWARE) {
                decoders.filter { it.softwareOnly }
            } else {
                decoders.filterNot { it.softwareOnly }
            }
        }
    }

    private fun rebuildPlayer() {
        val session = mediaSession ?: return
        val old = player ?: return

        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val repeatMode = old.repeatMode
        val shuffleEnabled = old.shuffleModeEnabled

        val fresh = buildPlayer()
        player = fresh
        session.player = fresh

        if (items.isNotEmpty()) {
            fresh.setMediaItems(items, index.coerceIn(0, items.lastIndex), position)
            fresh.prepare()
        }
        fresh.repeatMode = repeatMode
        fresh.shuffleModeEnabled = shuffleEnabled
        fresh.playWhenReady = playWhenReady
        old.release()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = mediaSession?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    private companion object {
        const val DECODER_AUTO = 0
        const val DECODER_SOFTWARE = 1
        const val DECODER_HARDWARE = 2
    }
}
