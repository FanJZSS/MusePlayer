package com.muse.player.ui.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.muse.player.data.HistoryRepository
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SubtitleFinder
import com.muse.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TrackOption(
    val label: String,
    val selected: Boolean
)

data class PlayerUiState(
    val connected: Boolean = false,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val speed: Float = 1f,
    val aspectMode: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesAvailable: Boolean = false,
    val itemIndex: Int = 0,
    val itemCount: Int = 0,
    val error: String? = null
)

@OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    var player: MediaController? = null
        private set

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingStart: PendingStart? = null
    private var ticker: Job? = null
    private var startJob: Job? = null
    private var tickCount = 0
    private var entries: List<PlayEntry> = emptyList()

    private data class PendingStart(val entries: List<PlayEntry>, val index: Int, val positionMs: Long)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    error = "播放失败：${error.errorCodeName}",
                    isBuffering = false
                )
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update {
                it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height)
            }
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    player = controller
                    controller.addListener(listener)
                    _state.update { it.copy(connected = true) }
                    pendingStart?.let {
                        pendingStart = null
                        start(it.entries, it.index, it.positionMs)
                    }
                    startTicker()
                } catch (_: Throwable) {
                    _state.update { it.copy(error = "无法连接播放服务") }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** 设置播放队列并开始播放 */
    fun start(entries: List<PlayEntry>, index: Int, positionMs: Long) {
        val controller = player
        if (controller == null) {
            pendingStart = PendingStart(entries, index, positionMs)
            return
        }
        if (entries.isEmpty()) return
        this.entries = entries
        val safeIndex = index.coerceIn(0, entries.lastIndex)
        val startPosition = positionMs.coerceAtLeast(0L)
        // 字幕文件查找涉及磁盘遍历，放到 IO 线程，避免拖慢起播
        startJob?.cancel()
        startJob = viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { entries.map { buildMediaItem(it) } }
            val current = player ?: return@launch
            current.setMediaItems(items, safeIndex, startPosition)
            current.prepare()
            current.playWhenReady = true
            _state.update { it.copy(error = null) }
        }
    }

    private fun buildMediaItem(entry: PlayEntry): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(entry.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist("Muse Player")
                    .build()
            )

        if (entry.isNetwork) {
            inferMimeType(entry.uri)?.let { builder.setMimeType(it) }
        }

        val subtitlePaths = LinkedHashSet<String>()
        if (!entry.isNetwork && SettingsRepository.state.value.autoSubtitles) {
            subtitlePaths += SubtitleFinder.findFor(entry.path)
        }
        subtitlePaths += entry.subtitleFiles

        val configs = subtitlePaths.mapNotNull { subtitleConfig(it) }
        if (configs.isNotEmpty()) {
            builder.setSubtitleConfigurations(configs)
        }
        return builder.build()
    }

    private fun subtitleConfig(path: String): MediaItem.SubtitleConfiguration? {
        val file = File(path)
        if (!file.exists()) return null
        val mime = when (file.extension.lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(mime)
            .setLabel(file.name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun inferMimeType(url: String): String? {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            clean.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    private fun refresh(player: Player) {
        val audioTracks = mutableListOf<TrackOption>()
        val subtitleTracks = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        audioTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, audioTracks.size, true),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        subtitleTracks += TrackOption(
                            label = trackLabel(format.label, format.language, format.codecs, subtitleTracks.size, false),
                            selected = group.isTrackSelected(i)
                        )
                    }
                }
            }
        }

        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        _state.update {
            it.copy(
                connected = true,
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                    ?: entries.getOrNull(player.currentMediaItemIndex)?.title
                    ?: "",
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                hasPrev = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
                speed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
                shuffleEnabled = player.shuffleModeEnabled,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                subtitlesAvailable = !textDisabled && subtitleTracks.any { it.selected },
                itemIndex = player.currentMediaItemIndex,
                itemCount = player.mediaItemCount
            )
        }
    }

    private fun trackLabel(
        label: String?,
        language: String?,
        codecs: String?,
        index: Int,
        isAudio: Boolean
    ): String {
        val base = label
            ?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }?.uppercase()
            ?: "${if (isAudio) "音轨" else "字幕"} ${index + 1}"
        return if (codecs.isNullOrBlank()) base else "$base · $codecs"
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val controller = player
                if (controller != null) {
                    val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                    _state.update {
                        it.copy(
                            positionMs = controller.currentPosition.coerceAtLeast(0),
                            durationMs = duration,
                            isPlaying = controller.isPlaying,
                            isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                            hasPrev = controller.hasPreviousMediaItem(),
                            hasNext = controller.hasNextMediaItem()
                        )
                    }
                    tickCount++
                    if (tickCount >= 10) {
                        tickCount = 0
                        if (controller.isPlaying) saveCurrentProgress()
                    }
                }
                delay(500)
            }
        }
    }

    fun togglePlay() {
        val controller = player ?: return
        if (controller.isPlaying) {
            controller.pause()
            saveCurrentProgress()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(0)
            }
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = player ?: return
        val target = positionMs.coerceAtLeast(0L)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun seekBy(deltaMs: Long) {
        val controller = player ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (controller.currentPosition + deltaMs).coerceIn(0L, duration)
        controller.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun playNext() {
        val controller = player ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        val controller = player ?: return
        if (controller.currentPosition > 5_000) {
            controller.seekTo(0)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        } else {
            controller.seekTo(0)
        }
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** 循环模式：关闭 → 列表循环 → 单曲循环 → 关闭 */
    fun cycleRepeatMode() {
        val controller = player ?: return
        val next = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    /** 切换随机播放 */
    fun toggleShuffle() {
        val controller = player ?: return
        val enabled = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = enabled
        _state.update { it.copy(shuffleEnabled = enabled) }
    }

    fun setAspectMode(mode: Int) {
        _state.update { it.copy(aspectMode = mode) }
    }

    fun selectAudioTrack(index: Int) {
        val controller = player ?: return
        val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(index) ?: return
        controller.trackSelectionParameters = controller.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    fun selectSubtitle(index: Int) {
        val controller = player ?: return
        val builder = controller.trackSelectionParameters.buildUpon()
        if (index < 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val groups = controller.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val group = groups.getOrNull(index) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        controller.trackSelectionParameters = builder.build()
    }

    /** 加载用户选择的外挂字幕，保持当前进度 */
    fun addSubtitle(uri: Uri, displayName: String) {
        val controller = player ?: return
        val index = controller.currentMediaItemIndex
        if (index !in 0 until controller.mediaItemCount) return
        val current = controller.getMediaItemAt(index)
        val mime = when (displayName.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(displayName)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val local = current.localConfiguration ?: return
        val updatedItem = MediaItem.Builder()
            .setUri(local.uri)
            .setMimeType(local.mimeType)
            .setMediaMetadata(current.mediaMetadata)
            .setSubtitleConfigurations(local.subtitleConfigurations + config)
            .build()
        val items = (0 until controller.mediaItemCount)
            .map { controller.getMediaItemAt(it) }
            .toMutableList()
        if (index !in items.indices) return
        items[index] = updatedItem
        val position = controller.currentPosition
        val wasPlaying = controller.isPlaying
        controller.setMediaItems(items, index, position)
        controller.prepare()
        if (wasPlaying) controller.play()
    }

    fun retry() {
        val controller = player ?: return
        _state.update { it.copy(error = null) }
        controller.prepare()
        controller.play()
    }

    fun pauseAndSave() {
        player?.pause()
        saveCurrentProgress()
    }

    fun saveProgress() {
        saveCurrentProgress()
    }

    private fun saveCurrentProgress() {
        val controller = player ?: return
        val item = controller.currentMediaItem ?: return
        val index = controller.currentMediaItemIndex
        val entry = entries.getOrNull(index)
        if (entry?.isNetwork == true) return
        val uri = item.localConfiguration?.uri?.toString()
            ?: entry?.uri
            ?: return
        val duration = controller.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: entry?.durationMs
            ?: 0L
        val position = controller.currentPosition
        val title = item.mediaMetadata.title?.toString() ?: entry?.title ?: ""
        if (position > 0) {
            HistoryRepository.record(uri, title, position, duration)
        }
    }

    override fun onCleared() {
        saveCurrentProgress()
        ticker?.cancel()
        ticker = null
        player?.removeListener(listener)
        player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }
}
