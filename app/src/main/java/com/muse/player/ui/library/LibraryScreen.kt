package com.muse.player.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import com.muse.player.data.HistoryEntry
import com.muse.player.data.HistoryRepository
import com.muse.player.data.PlayEntry
import com.muse.player.data.SettingsRepository
import com.muse.player.data.SortField
import com.muse.player.data.VideoItem
import com.muse.player.ui.network.NetworkTab
import com.muse.player.ui.network.NetworkViewModel
import com.muse.player.ui.player.PlayerLauncher
import com.muse.player.util.MediaPermissions
import com.muse.player.util.formatDate
import com.muse.player.util.formatDuration
import com.muse.player.util.formatSize

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    viewModel: LibraryViewModel,
    networkViewModel: NetworkViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(MediaPermissions.granted(context)) }
    var allFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }
    var showSearch by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showNetworkDialog by remember { mutableStateOf(false) }
    var infoVideo by remember { mutableStateOf<VideoItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = MediaPermissions.granted(context)
        if (hasPermission) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        val permissions = MediaPermissions.required() +
            listOfNotNull(MediaPermissions.notificationPermission())
        if (!hasPermission) permissionLauncher.launch(permissions)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = MediaPermissions.granted(context)
                if (granted != hasPermission) {
                    hasPermission = granted
                    if (granted) viewModel.refresh()
                }
                // 从系统设置授予“所有文件访问权限”返回后，重新扫描系统隐藏目录
                val allFiles = hasAllFilesAccess()
                if (allFiles != allFilesAccess) {
                    allFilesAccess = allFiles
                    if (allFiles && SettingsRepository.state.value.showSystemHiddenFolders) {
                        viewModel.refresh()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission && state.tab != 2) {
        PermissionScreen(
            onRequest = {
                permissionLauncher.launch(
                    MediaPermissions.required() +
                        listOfNotNull(MediaPermissions.notificationPermission())
                )
            },
            onOpenSettings = { openAppSettings(context) },
            onBrowseNetwork = { viewModel.setTab(2) }
        )
        return
    }

    fun playVideo(video: VideoItem, queue: List<VideoItem>) {
        if (queue.isEmpty()) return
        val entries = queue.map { it.toPlayEntry() }
        val index = queue.indexOfFirst { it.id == video.id }.coerceAtLeast(0)
        val resume = if (SettingsRepository.state.value.autoResume) {
            HistoryRepository.progressOf(video.uri.toString())
        } else {
            0L
        }
        PlayerLauncher.open(context, entries, index, resume)
    }

    val tabs = listOf(
        LibraryTab("媒体库", Icons.Rounded.VideoLibrary),
        LibraryTab("历史", Icons.Rounded.History),
        LibraryTab("网络", Icons.Rounded.Dns)
    )
    // 媒体库视图模式：0 = 视频，1 = 文件夹
    var mediaMode by rememberSaveable { mutableIntStateOf(0) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val pagerState = rememberPagerState(initialPage = state.tab) { tabs.size }

    val scope = rememberCoroutineScope()

    // 页码一变化就同步 tab，让导航高亮与内容切换同步
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != viewModel.state.value.tab) viewModel.setTab(page)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Text("Muse Player", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setQuery("")
                    }) {
                        Icon(
                            if (showSearch) Icons.Rounded.Close else Icons.Rounded.Search,
                            contentDescription = "搜索"
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Rounded.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortField.entries.forEach { field ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            field.label + if (state.sortField == field) {
                                                if (state.ascending) "  ↑" else "  ↓"
                                            } else {
                                                ""
                                            }
                                        )
                                    },
                                    onClick = {
                                        viewModel.setSortField(field)
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (state.sortField == field) {
                                            Icon(Icons.Rounded.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (state.grid) "列表视图" else "网格视图") },
                                onClick = {
                                    viewModel.toggleGrid()
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(
                                        if (state.grid) Icons.Rounded.ViewList
                                        else Icons.Rounded.GridView,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("网络串流") },
                                onClick = {
                                    showNetworkDialog = true
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Link, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("刷新媒体库") },
                                onClick = {
                                    viewModel.refresh()
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    navController.navigate("settings")
                                    showOverflow = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Settings, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (wide) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                    Spacer(Modifier.height(8.dp))
                    tabs.forEachIndexed { index, tab ->
                        NavigationRailItem(
                            selected = state.tab == index,
                            onClick = {
                                viewModel.setTab(index)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
            if (showSearch) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("搜索视频名称") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                            }
                        }
                    }
                )
            }

            if (!wide) {
                TabRow(
                    selectedTabIndex = state.tab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = state.tab == index,
                            onClick = {
                                viewModel.setTab(index)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            text = { Text(tab.title) }
                        )
                    }
                }
            }

            if (state.tab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = mediaMode == 0,
                        onClick = { mediaMode = 0 },
                        label = { Text("视频 ${state.videos.size}") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    FilterChip(
                        selected = mediaMode == 1,
                        onClick = { mediaMode = 1 },
                        label = { Text("文件夹 ${state.folders.size}") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            val progressMap = remember(state.history) {
                state.history.associate { it.uri to it.positionMs }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                0 -> {
                    if (mediaMode == 1) {
                        if (state.folders.isEmpty()) {
                            EmptyState("没有文件夹", "视频会按照所在目录归入文件夹")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(state.folders, key = { it.bucketId }) { folder ->
                                    FolderRow(
                                        folder = folder,
                                        onClick = {
                                            navController.navigate(
                                                "folder/${Uri.encode(folder.bucketId)}"
                                            )
                                        },
                                        hidden = folder.bucketId in state.hiddenFolderIds,
                                        onToggleHidden = {
                                            viewModel.toggleFolderHidden(folder.bucketId)
                                        }
                                    )
                                }
                            }
                        }
                    } else when {
                        state.loading -> LoadingBox()
                        state.videos.isEmpty() -> EmptyState(
                            title = if (state.filterActive) "没有匹配的视频" else "媒体库为空",
                            subtitle = if (state.filterActive) {
                                "试试其他关键词"
                            } else {
                                "将视频拷贝到设备后，下拉刷新或重新扫描"
                            }
                        )

                        state.grid -> LazyVerticalGrid(
                            columns = if (wide) {
                                GridCells.Adaptive(200.dp)
                            } else {
                                GridCells.Fixed(2)
                            },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.videos, key = { it.id }) { video ->
                                VideoCard(
                                    video = video,
                                    progressMs = progressMap[video.uri.toString()] ?: 0L,
                                    onClick = { playVideo(video, state.videos) },
                                    onLongClick = { infoVideo = video }
                                )
                            }
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(state.videos, key = { it.id }) { video ->
                                VideoRow(
                                    video = video,
                                    progressMs = progressMap[video.uri.toString()] ?: 0L,
                                    onClick = { playVideo(video, state.videos) },
                                    onLongClick = { infoVideo = video }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (state.history.isEmpty()) {
                        EmptyState("暂无播放记录", "播放过的视频会出现在这里")
                    } else {
                        val videoByUri = remember(state.videos) {
                            state.videos.associateBy { it.uri.toString() }
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${state.history.size} 条记录",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = viewModel::clearHistory) {
                                        Text("清空")
                                    }
                                }
                            }
                            items(state.history, key = { it.uri }) { entry ->
                                HistoryRow(
                                    entry = entry,
                                    video = videoByUri[entry.uri],
                                    onClick = { playHistory(entry, videoByUri[entry.uri], context) },
                                    onRemove = { viewModel.removeHistory(entry.uri) }
                                )
                            }
                        }
                    }
                }

                else -> {
                    NetworkTab(
                        viewModel = networkViewModel,
                        onOpenServer = { server ->
                            networkViewModel.openServer(server)
                            navController.navigate("network_browser")
                        }
                    )
                }
                }
            }
            }
        }
    }

    if (showNetworkDialog) {
        NetworkDialog(
            onDismiss = { showNetworkDialog = false },
            onPlay = { url ->
                val title = url.substringAfterLast('/').ifBlank { "网络串流" }
                PlayerLauncher.open(
                    context,
                    listOf(PlayEntry(uri = url, title = title, isNetwork = true)),
                    0,
                    0L
                )
                showNetworkDialog = false
            }
        )
    }

    infoVideo?.let { video ->
        VideoInfoDialog(video = video, onDismiss = { infoVideo = null })
    }
}

private fun playHistory(entry: HistoryEntry, video: VideoItem?, context: Context) {
    val playEntry = PlayEntry(
        uri = entry.uri,
        title = entry.title,
        id = video?.id ?: -1L,
        durationMs = entry.durationMs,
        path = video?.path
    )
    val resume = if (SettingsRepository.state.value.autoResume) entry.positionMs else 0L
    PlayerLauncher.open(context, listOf(playEntry), 0, resume)
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PermissionScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onBrowseNetwork: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.VideoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text("需要视频访问权限", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Muse Player 需要读取设备上的视频，才能建立媒体库并播放。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text("授予权限")
        }
        TextButton(onClick = onOpenSettings) {
            Text("前往系统设置")
        }
        if (onBrowseNetwork != null) {
            TextButton(onClick = onBrowseNetwork) {
                Text("局域网浏览")
            }
        }
    }
}

@Composable
private fun NetworkDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开网络串流") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/video.m3u8") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "支持 HTTP / HTTPS / HLS / DASH / RTSP 地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = url.trim()
                if (trimmed.startsWith("http://") ||
                    trimmed.startsWith("https://") ||
                    trimmed.startsWith("rtsp://") ||
                    trimmed.startsWith("rtmp://")
                ) {
                    onPlay(trimmed)
                } else {
                    error = "请输入合法的流媒体地址"
                }
            }) {
                Text("播放")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun VideoInfoDialog(video: VideoItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = video.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InfoLine("时长", formatDuration(video.durationMs))
                InfoLine("大小", formatSize(video.size))
                if (video.width > 0) {
                    InfoLine("分辨率", "${video.width} × ${video.height}")
                }
                InfoLine("加入时间", formatDate(video.dateAdded))
                InfoLine("文件夹", video.bucketName)
                InfoLine("路径", video.path ?: video.uri.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private data class LibraryTab(val title: String, val icon: ImageVector)

private fun openAppSettings(context: Context) {
    val intent = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    context.startActivity(intent)
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
