package com.muse.player.ui.library

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muse.player.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 文件管理器式浏览：目录 + 视频文件 */
private data class BrowseEntry(
    val file: File,
    val isDirectory: Boolean,
    val video: VideoItem?
)

private val BROWSE_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v",
    "ts", "m2ts", "mpg", "mpeg", "3gp", "3g2", "rmvb", "rm", "asf", "vob"
)

@Composable
fun BrowseTab(
    onPlay: (queue: List<VideoItem>, index: Int) -> Unit
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var currentPath by rememberSaveable { mutableStateOf(root.absolutePath) }
    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    // 系统返回键：有上级目录时先回到上一级，避免直接退出应用
    BackHandler(enabled = currentPath != root.absolutePath) {
        val parent = File(currentPath).parentFile
        if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
            currentPath = parent.absolutePath
        }
    }

    LaunchedEffect(currentPath) {
        loading = true
        failed = false
        val dir = File(currentPath)
        val loaded = withContext(Dispatchers.IO) { readEntries(dir) }
        failed = loaded == null
        entries = loaded ?: emptyList()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val parent = File(currentPath).parentFile
                    if (parent != null && currentPath != root.absolutePath) {
                        currentPath = parent.absolutePath
                    }
                },
                enabled = currentPath != root.absolutePath
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "上一级")
            }
            val displayPath = currentPath
                .removePrefix(root.absolutePath)
                .ifBlank { "/" }
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            failed -> EmptyState("无法读取该目录", "请确认已授予存储访问权限")
            entries.isEmpty() -> EmptyState("空文件夹", "这里没有子文件夹或视频文件")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(entries, key = { it.file.absolutePath }) { entry ->
                    BrowseRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                currentPath = entry.file.absolutePath
                            } else {
                                val videos = entries.mapNotNull { it.video }
                                val index = videos.indexOfFirst {
                                    it.path == entry.file.absolutePath
                                }.coerceAtLeast(0)
                                onPlay(videos, index)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(entry: BrowseEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) {
                Icons.Rounded.Folder
            } else {
                Icons.Rounded.Movie
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = if (entry.isDirectory) {
                "文件夹"
            } else {
                formatSize(entry.file.length())
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.isDirectory) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 读取目录内容；无权限或读取失败时返回 null */
private fun readEntries(dir: File): List<BrowseEntry>? {
    val children = try {
        dir.listFiles()
    } catch (_: Exception) {
        null
    } ?: return null

    val dirs = ArrayList<BrowseEntry>()
    val videos = ArrayList<BrowseEntry>()
    for (file in children) {
        try {
            if (file.isDirectory) {
                if (file.name == "Android") continue
                dirs += BrowseEntry(file, true, null)
            } else if (file.extension.lowercase() in BROWSE_VIDEO_EXTENSIONS && file.length() > 0) {
                videos += BrowseEntry(file, false, file.toBrowseVideoItem())
            }
        } catch (_: Exception) {
            // 单个条目不可读时跳过
        }
    }
    dirs.sortBy { it.file.name.lowercase() }
    videos.sortBy { it.file.name.lowercase() }
    return dirs + videos
}

private fun File.toBrowseVideoItem(): VideoItem {
    val parent = parentFile
    val name = name
    return VideoItem(
        id = -((absolutePath.hashCode().toLong() and Long.MAX_VALUE) + 1),
        uri = Uri.fromFile(this),
        title = name.substringBeforeLast('.').ifBlank { name },
        path = absolutePath,
        durationMs = 0L,
        size = length(),
        dateAdded = lastModified(),
        bucketId = "browse:${parent?.absolutePath ?: absolutePath}",
        bucketName = parent?.name ?: "文件",
        width = 0,
        height = 0,
        mimeType = null
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
