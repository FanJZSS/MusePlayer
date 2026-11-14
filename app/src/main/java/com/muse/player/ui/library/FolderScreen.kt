package com.muse.player.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.muse.player.data.HistoryRepository
import com.muse.player.data.SettingsRepository
import com.muse.player.data.VideoItem
import com.muse.player.ui.player.PlayerLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    navController: NavController,
    viewModel: LibraryViewModel,
    bucketId: String
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by SettingsRepository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    val videos = remember(bucketId, state.videos, state.sortField, state.ascending) {
        viewModel.videosInFolder(bucketId)
    }
    val folderName = state.folders.firstOrNull { it.bucketId == bucketId }?.name ?: "文件夹"
    val progressMap = remember(state.history) {
        state.history.associate { it.uri to it.positionMs }
    }

    fun play(video: VideoItem, fromStart: Boolean = false) {
        if (videos.isEmpty()) return
        val entries = videos.map { it.toPlayEntry() }
        val index = videos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val resume = if (!fromStart && SettingsRepository.state.value.autoResume) {
            HistoryRepository.progressOf(video.uri.toString())
        } else {
            0L
        }
        PlayerLauncher.open(context, entries, index, resume)
    }

    var actionVideo by remember { mutableStateOf<VideoItem?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = folderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    Text(
                        text = "${videos.size} 个视频",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(
                        onClick = {
                            val first = videos.firstOrNull() ?: return@IconButton
                            play(first)
                        }
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "播放全部")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (wide || settings.gridViewMode) {
            LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(200.dp) else GridCells.Fixed(2),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(if (wide) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 10.dp)
            ) {
                gridItems(videos, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        progressMs = progressMap[video.uri.toString()] ?: 0L,
                        onClick = { play(video) },
                        onLongClick = { actionVideo = video }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoRow(
                        video = video,
                        progressMs = progressMap[video.uri.toString()] ?: 0L,
                        onClick = { play(video) },
                        onLongClick = { actionVideo = video }
                    )
                }
            }
        }
    }

    actionVideo?.let { video ->
        ActionSheet(
            title = video.title,
            actions = listOf(
                MenuAction("播放", Icons.Rounded.PlayArrow) { play(video) },
                MenuAction("从头播放", Icons.Rounded.Replay) { play(video, fromStart = true) },
                MenuAction("复制路径", Icons.Rounded.ContentCopy) {
                    val text = video.path ?: video.uri.toString()
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    manager?.setPrimaryClip(ClipData.newPlainText("Muse Player", text))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            ),
            onDismiss = { actionVideo = null }
        )
    }
}
