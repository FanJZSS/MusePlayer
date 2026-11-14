package com.muse.player.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val context = LocalContext.current

    val videos = remember(bucketId, state.videos) {
        viewModel.videosInFolder(bucketId)
    }
    val folderName = state.folders.firstOrNull { it.bucketId == bucketId }?.name ?: "文件夹"
    val progressMap = remember(state.history) {
        state.history.associate { it.uri to it.positionMs }
    }

    fun play(video: VideoItem) {
        if (videos.isEmpty()) return
        val entries = videos.map { it.toPlayEntry() }
        val index = videos.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val resume = if (SettingsRepository.state.value.autoResume) {
            HistoryRepository.progressOf(video.uri.toString())
        } else {
            0L
        }
        PlayerLauncher.open(context, entries, index, resume)
    }

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
                    onLongClick = {}
                )
            }
        }
    }
}
