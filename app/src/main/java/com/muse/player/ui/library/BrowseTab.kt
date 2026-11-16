package com.muse.player.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings as AndroidSettings
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

/** 可浏览的存储根目录（内部存储 / SD 卡 / U 盘） */
private data class StorageRoot(val label: String, val dir: File)

private val BROWSE_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "m4v",
    "ts", "m2ts", "mpg", "mpeg", "3gp", "3g2", "rmvb", "rm", "asf", "vob"
)

@Composable
fun BrowseTab(
    onPlay: (queue: List<VideoItem>, index: Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val roots = remember(context) { loadStorageRoots(context) }
    val primaryPath = roots.firstOrNull()?.dir?.absolutePath
        ?: Environment.getExternalStorageDirectory().absolutePath

    var currentPath by rememberSaveable { mutableStateOf(primaryPath) }
    var showVolumes by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    val currentRoot = roots
        .filter {
            currentPath == it.dir.absolutePath ||
                currentPath.startsWith(it.dir.absolutePath + File.separator)
        }
        .maxByOrNull { it.dir.absolutePath.length }
        ?: roots.firstOrNull()
    val rootPath = currentRoot?.dir?.absolutePath ?: primaryPath
    val atRoot = currentPath == rootPath

    // 从系统设置授予权限返回后刷新目录
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
                reloadKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 存储卡被拔出后回到内部存储
    LaunchedEffect(roots) {
        val valid = roots.any {
            currentPath == it.dir.absolutePath ||
                currentPath.startsWith(it.dir.absolutePath + File.separator)
        }
        if (!valid) currentPath = primaryPath
    }

    // 系统返回键：有上级目录时先回到上一级，避免直接退出应用
    BackHandler(enabled = !atRoot) {
        val parent = File(currentPath).parentFile
        val parentPath = parent?.absolutePath
        currentPath = if (
            parentPath != null &&
            (parentPath == rootPath || parentPath.startsWith(rootPath + File.separator))
        ) {
            parentPath
        } else {
            rootPath
        }
    }

    LaunchedEffect(currentPath, reloadKey) {
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
                    val parentPath = parent?.absolutePath
                    if (parentPath != null && (
                            parentPath == rootPath ||
                                parentPath.startsWith(rootPath + File.separator)
                            )
                    ) {
                        currentPath = parentPath
                    }
                },
                enabled = !atRoot
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "上一级")
            }
            val displayPath = currentPath
                .removePrefix(rootPath)
                .ifBlank { "/" }
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { showVolumes = true }) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = currentRoot?.label ?: "存储",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = showVolumes,
                    onDismissRequest = { showVolumes = false }
                ) {
                    roots.forEach { root ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(root.label)
                                    Text(
                                        text = root.dir.absolutePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Storage,
                                    contentDescription = null,
                                    tint = if (root.dir.absolutePath == rootPath) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            onClick = {
                                currentPath = root.dir.absolutePath
                                showVolumes = false
                            }
                        )
                    }
                }
            }
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "无法读取该目录",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesAccess ->
                            "浏览 SD 卡、U 盘需要「所有文件访问权限」"
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                            "Android 10 需要在系统设置中允许本应用访问存储"
                        else -> "请确认存储设备已挂载并授予存储访问权限"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesAccess) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { openAllFilesAccess(context) }) {
                        Text("前往授权")
                    }
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { openAppSettings(context) }) {
                        Text("打开应用设置")
                    }
                }
            }

            entries.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val needAllFiles =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesAccess
                Text(
                    text = if (needAllFiles) "这里看不到文件" else "空文件夹",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (needAllFiles) {
                        // 没有「所有文件访问权限」时，系统会隐藏未被媒体库收录的文件，
                        // 目录看起来是空的
                        "浏览全部文件需要「所有文件访问权限」，授权后即可显示"
                    } else {
                        "这里没有子文件夹或视频文件"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = currentPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (needAllFiles) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { openAllFilesAccess(context) }) {
                        Text("前往授权")
                    }
                }
            }
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

/** 收集所有可浏览的存储根目录：内部存储、SD 卡、U 盘等 */
private fun loadStorageRoots(context: Context): List<StorageRoot> {
    val primary = Environment.getExternalStorageDirectory()
    val found = LinkedHashMap<String, StorageRoot>()
    found[primary.absolutePath] = StorageRoot("内部存储", primary)

    // 通过应用的外部存储目录反推挂载点，可覆盖 SD 卡与部分 U 盘
    context.getExternalFilesDirs(null).forEach { dir ->
        if (dir == null) return@forEach
        val path = dir.absolutePath
        val cut = path.indexOf("/Android/")
        if (cut <= 0) return@forEach
        val rootPath = path.substring(0, cut)
        if (found.containsKey(rootPath) || rootPath == primary.absolutePath) return@forEach
        val rootDir = File(rootPath)
        if (!rootDir.exists()) return@forEach
        found[rootPath] = StorageRoot(volumeLabel(context, rootPath), rootDir)
    }

    // 兜底扫描 /storage，覆盖未通过 getExternalFilesDirs 暴露的外接存储
    try {
        File("/storage").listFiles()?.forEach { dir ->
            val name = dir.name
            if (!dir.isDirectory) return@forEach
            if (name == "emulated" || name == "self" || name == "enc_emulated") return@forEach
            val rootPath = dir.absolutePath
            if (found.containsKey(rootPath)) return@forEach
            if (!dir.canRead()) return@forEach
            if (sameFile(dir, primary)) return@forEach
            found[rootPath] = StorageRoot(volumeLabel(context, rootPath), dir)
        }
    } catch (_: Exception) {
    }

    // 多个存储同名时附上挂载点名称便于区分
    val labelCounts = found.values.groupingBy { it.label }.eachCount()
    return found.values.map { root ->
        if ((labelCounts[root.label] ?: 0) > 1) {
            root.copy(label = "${root.label} (${root.dir.name})")
        } else {
            root
        }
    }
}

private fun sameFile(a: File, b: File): Boolean = try {
    a.canonicalPath == b.canonicalPath
} catch (_: Exception) {
    a.absolutePath == b.absolutePath
}

/** 根据挂载路径匹配系统存储卷，取用户可见的名称（如“SD 卡”“U 盘”） */
private fun volumeLabel(context: Context, path: String): String {
    val fallback = "外接存储"
    return try {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volume = manager?.storageVolumes?.firstOrNull { vol ->
            val uuid = vol.uuid
            uuid != null && path.contains(uuid, ignoreCase = true)
        }
        volume?.getDescription(context)?.takeIf { it.isNotBlank() } ?: fallback
    } catch (_: Exception) {
        fallback
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val appIntent = Intent(
        AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    try {
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: Exception) {
        }
    }
}

private fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    } catch (_: Exception) {
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
