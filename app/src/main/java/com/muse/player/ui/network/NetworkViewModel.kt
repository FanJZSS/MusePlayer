package com.muse.player.ui.network

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.player.data.network.DiscoveredServer
import com.muse.player.data.network.LanDiscovery
import com.muse.player.data.network.NetworkRepository
import com.muse.player.data.network.NetworkServer
import com.muse.player.data.network.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowseState(
    val server: NetworkServer? = null,
    val path: String = "",
    val startPath: String = "",
    val entries: List<RemoteFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
) {
    val canGoUp: Boolean get() = server != null && path != startPath
}

data class DiscoveryState(
    val scanning: Boolean = false,
    val scanned: Int = 0,
    val total: Int = 0,
    val results: List<DiscoveredServer> = emptyList(),
    val finished: Boolean = false
)

class NetworkViewModel(application: Application) : AndroidViewModel(application) {

    val servers: StateFlow<List<NetworkServer>> = NetworkRepository.servers

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private var job: Job? = null
    private var discoveryJob: Job? = null

    /** 自动搜索局域网中的共享设备 */
    fun discoverLan() {
        if (discoveryJob?.isActive == true) return
        _discovery.value = DiscoveryState(scanning = true)
        discoveryJob = viewModelScope.launch {
            try {
                val results = LanDiscovery.scan { scanned, total ->
                    _discovery.value = _discovery.value.copy(
                        scanned = scanned,
                        total = total
                    )
                }
                _discovery.value = _discovery.value.copy(
                    scanning = false,
                    finished = true,
                    results = results
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _discovery.value = _discovery.value.copy(scanning = false, finished = true)
            }
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _discovery.value = _discovery.value.copy(scanning = false, finished = true)
    }

    fun closeDiscovery() {
        if (discoveryJob?.isActive == true) discoveryJob?.cancel()
        discoveryJob = null
        _discovery.value = DiscoveryState()
    }

    fun openServer(server: NetworkServer) {
        val root = NetworkRepository.rootPath(server)
        _browse.value = BrowseState(
            server = server,
            path = root,
            startPath = root,
            loading = true
        )
        load(server, root)
    }

    fun openFolder(folder: RemoteFile) {
        val state = _browse.value
        val server = state.server ?: return
        if (!folder.isDirectory) return
        _browse.value = state.copy(
            path = folder.relativePath,
            entries = emptyList(),
            loading = true,
            error = null
        )
        load(server, folder.relativePath)
    }

    fun goUp() {
        val state = _browse.value
        val server = state.server ?: return
        if (!state.canGoUp) return
        val parent = state.path.substringBeforeLast('/', "")
        val target = if (parent.length < state.startPath.length) state.startPath else parent
        if (target == state.path) return
        _browse.value = state.copy(
            path = target,
            entries = emptyList(),
            loading = true,
            error = null
        )
        load(server, target)
    }

    fun refresh() {
        val state = _browse.value
        val server = state.server ?: return
        _browse.value = state.copy(loading = true, error = null)
        load(server, state.path)
    }

    fun closeServer() {
        job?.cancel()
        job = null
        _browse.value = BrowseState()
    }

    fun saveServer(server: NetworkServer): NetworkServer {
        val stored = NetworkRepository.save(server)
        val browsing = _browse.value
        if (browsing.server?.id == stored.id) {
            val root = NetworkRepository.rootPath(stored)
            _browse.value = browsing.copy(
                server = stored,
                path = root,
                startPath = root,
                entries = emptyList(),
                loading = true,
                error = null
            )
            load(stored, root)
        }
        return stored
    }

    fun deleteServer(id: String) {
        NetworkRepository.delete(id)
        if (_browse.value.server?.id == id) closeServer()
    }

    private fun load(server: NetworkServer, path: String) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val entries = NetworkRepository.list(server, path)
                val state = _browse.value
                if (state.server?.id == server.id && state.path == path) {
                    _browse.value = state.copy(
                        entries = entries,
                        loading = false,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val state = _browse.value
                if (state.server?.id == server.id && state.path == path) {
                    _browse.value = state.copy(
                        loading = false,
                        error = e.message?.takeIf { it.isNotBlank() } ?: "读取目录失败"
                    )
                }
            }
        }
    }
}
