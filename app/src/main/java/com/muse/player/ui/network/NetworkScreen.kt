package com.muse.player.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.muse.player.data.PlayEntry
import com.muse.player.data.network.DiscoveredServer
import com.muse.player.data.network.NetworkRepository
import com.muse.player.data.network.NetworkServer
import com.muse.player.data.network.RemoteFile
import com.muse.player.data.network.ServerType
import com.muse.player.ui.library.EmptyState
import com.muse.player.ui.player.PlayerLauncher
import com.muse.player.ui.theme.MuseBlue
import com.muse.player.util.formatSize

/**
 * 媒体库中的“网络”页：局域网服务器列表。
 */
@Composable
fun NetworkTab(viewModel: NetworkViewModel, onOpenServer: (NetworkServer) -> Unit) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val discovery by viewModel.discovery.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var showDiscovery by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<NetworkServer?>(null) }
    var editing by remember { mutableStateOf<NetworkServer?>(null) }
    var deleting by remember { mutableStateOf<NetworkServer?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${servers.size} 台服务器",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                showDiscovery = true
                viewModel.discoverLan()
            }) {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("搜索")
            }
            TextButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加")
            }
        }

        if (servers.isEmpty()) {
            EmptyState(
                title = "还没有局域网服务器",
                subtitle = "点击右上角“搜索”自动发现设备，或“添加”手动填写"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        onClick = { onOpenServer(server) },
                        onEdit = { editing = server },
                        onDelete = { deleting = server }
                    )
                }
            }
        }
    }

    if (showDiscovery) {
        DiscoveryDialog(
            state = discovery,
            savedServers = servers,
            onScanAgain = viewModel::discoverLan,
            onSelect = { result ->
                showDiscovery = false
                viewModel.closeDiscovery()
                prefill = NetworkServer(
                    type = result.type,
                    name = result.name.ifBlank { result.host },
                    host = result.host,
                    port = result.port
                )
            },
            onDismiss = {
                showDiscovery = false
                viewModel.closeDiscovery()
            }
        )
    }

    prefill?.let { server ->
        ServerEditDialog(
            initial = server,
            onDismiss = { prefill = null },
            onSave = {
                viewModel.saveServer(it)
                prefill = null
            }
        )
    }

    if (showAdd) {
        ServerEditDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = {
                viewModel.saveServer(it)
                showAdd = false
            }
        )
    }

    editing?.let { server ->
        ServerEditDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveServer(it)
                editing = null
            }
        )
    }

    deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除「${server.name.ifBlank { server.host }}」吗？仅删除本机配置。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteServer(server.id)
                    deleting = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DiscoveryDialog(
    state: DiscoveryState,
    savedServers: List<NetworkServer>,
    onScanAgain: () -> Unit,
    onSelect: (DiscoveredServer) -> Unit,
    onDismiss: () -> Unit
) {
    val available = remember(state.results, savedServers) {
        state.results.filter { result ->
            savedServers.none {
                it.type == result.type && it.host == result.host && it.port == result.port
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索局域网") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.scanning || !state.finished -> {
                        LinearProgressIndicator(
                            progress = {
                                if (state.total > 0) state.scanned.toFloat() / state.total else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "正在扫描局域网… ${state.scanned} / ${state.total}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "已发现 ${available.size} 台设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    available.isEmpty() -> {
                        Text(
                            text = "未发现共享设备",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "请确认手机与电脑 / NAS 连接同一 Wi-Fi，" +
                                "并且已开启 SMB、FTP 或 WebDAV 共享。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        Text(
                            text = "发现 ${available.size} 台设备，点击可直接添加",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(
                                available,
                                key = { "${it.type.name}-${it.host}-${it.port}" }
                            ) { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(result) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = MuseBlue,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = result.name.ifBlank { result.host },
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "${result.type.label} · ${result.host}:${result.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.scanning || !state.finished) {
                TextButton(onClick = onDismiss) { Text("取消") }
            } else {
                TextButton(onClick = onScanAgain) { Text("重新搜索") }
            }
        },
        dismissButton = {
            if (!state.scanning && state.finished) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun ServerRow(
    server: NetworkServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = MuseBlue)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name.ifBlank { server.host },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${server.type.label} · ${server.displayAddress}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "服务器操作")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditDialog(
    initial: NetworkServer?,
    onDismiss: () -> Unit,
    onSave: (NetworkServer) -> Unit
) {
    var type by remember(initial) { mutableStateOf(initial?.type ?: ServerType.SMB) }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) {
        mutableStateOf((initial?.port ?: ServerType.SMB.defaultPort).toString())
    }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var path by remember(initial) { mutableStateOf(initial?.path.orEmpty()) }
    var passwordVisible by remember(initial) { mutableStateOf(false) }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial?.id.isNullOrBlank()) "添加服务器" else "编辑服务器")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerType.entries.forEach { item ->
                        FilterChip(
                            selected = type == item,
                            onClick = {
                                if (type != item) {
                                    port = item.defaultPort.toString()
                                    type = item
                                    error = null
                                }
                            },
                            label = { Text(item.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    placeholder = { Text("家庭 NAS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        error = null
                    },
                    label = { Text("主机 / IP") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Rounded.VisibilityOff
                                else Icons.Rounded.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("起始路径（可选）") },
                    placeholder = { Text(pathHint(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "SMB 需填写共享文件夹名，FTP/WebDAV 填写登录后的子目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
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
                if (host.isBlank()) {
                    error = "请填写主机地址"
                    return@TextButton
                }
                val portValue = port.toIntOrNull()
                if (portValue == null || portValue !in 1..65535) {
                    error = "端口需在 1-65535 之间"
                    return@TextButton
                }
                onSave(
                    NetworkServer(
                        id = initial?.id.orEmpty(),
                        type = type,
                        name = name.trim().ifBlank { host.trim() },
                        host = host.trim(),
                        port = portValue,
                        username = username.trim(),
                        password = password,
                        path = path.trim().trim('/')
                    )
                )
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun pathHint(type: ServerType): String = when (type) {
    ServerType.SMB -> "video/电影"
    ServerType.FTP -> "pub/video"
    ServerType.WEBDAV -> "dav/video"
}

/**
 * 远程目录浏览页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowserScreen(navController: NavController, viewModel: NetworkViewModel) {
    val state by viewModel.browse.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (viewModel.browse.value.server == null) {
            navController.popBackStack()
        }
    }

    BackHandler(enabled = state.canGoUp) {
        viewModel.goUp()
    }

    val server = state.server
    val visibleEntries = remember(state.entries) {
        state.entries.filter {
            it.isDirectory || NetworkRepository.isVideoFile(it.name)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.canGoUp) viewModel.goUp() else navController.popBackStack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = server?.name?.ifBlank { server.host } ?: "网络浏览",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (state.path.isBlank()) {
                                server?.displayAddress.orEmpty()
                            } else {
                                "/${state.path}"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when {
                state.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                state.error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::refresh) {
                        Text("重试")
                    }
                }

                visibleEntries.isEmpty() -> EmptyState(
                    title = "此目录没有视频",
                    subtitle = "换个目录，或检查服务器起始路径设置"
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(visibleEntries, key = { it.relativePath }) { entry ->
                        RemoteFileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    viewModel.openFolder(entry)
                                } else {
                                    playRemote(context, visibleEntries, entry)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun playRemote(
    context: android.content.Context,
    visibleEntries: List<RemoteFile>,
    clicked: RemoteFile
) {
    val videos = visibleEntries.filter { !it.isDirectory && NetworkRepository.isVideoFile(it.name) }
    if (videos.isEmpty()) return
    val queue = videos.map {
        PlayEntry(uri = it.uri, title = it.name, isNetwork = true)
    }
    val index = videos.indexOfFirst { it.uri == clicked.uri }.coerceAtLeast(0)
    PlayerLauncher.open(context, queue, index, 0L)
}

@Composable
private fun RemoteFileRow(entry: RemoteFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Movie,
                contentDescription = null,
                tint = if (entry.isDirectory) MuseBlue else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!entry.isDirectory && entry.size > 0) {
                Text(
                    text = formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
