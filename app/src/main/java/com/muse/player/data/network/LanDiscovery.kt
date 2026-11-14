package com.muse.player.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit

/** 自动搜索到的局域网共享设备 */
data class DiscoveredServer(
    val type: ServerType,
    val host: String,
    val port: Int,
    val name: String
)

/**
 * 局域网自动发现：扫描本机所在网段（按 /24 处理）的常见共享端口。
 * SMB 445、FTP 21、WebDAV 通过 HTTP OPTIONS 的 DAV 响应头确认。
 */
object LanDiscovery {

    private const val CONNECT_TIMEOUT_MS = 400
    private const val MAX_HOSTS = 1024
    private const val CONCURRENCY = 64

    private val webDavClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
            .writeTimeout(1200, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun scan(
        onProgress: (scanned: Int, total: Int) -> Unit
    ): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val hosts = localSubnetHosts()
        onProgress(0, hosts.size)
        if (hosts.isEmpty()) return@withContext emptyList()

        val found = ArrayList<DiscoveredServer>()
        val semaphore = Semaphore(CONCURRENCY)
        val lock = Any()
        var scanned = 0

        coroutineScope {
            hosts.map { host ->
                async {
                    semaphore.withPermit {
                        val hits = probe(host)
                        synchronized(lock) {
                            found += hits
                            scanned++
                            onProgress(scanned, hosts.size)
                        }
                    }
                }
            }.awaitAll()
        }

        found.sortedWith(compareBy({ it.type.ordinal }, { it.host }))
    }

    /** 本机所有 IPv4 地址所在 /24 网段的其余主机 */
    private fun localSubnetHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return emptyList()
        }
        for (nif in interfaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
            } catch (_: Exception) {
                continue
            }
            val addresses = try {
                Collections.list(nif.inetAddresses)
            } catch (_: Exception) {
                continue
            }
            for (address in addresses) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val ip = address.hostAddress ?: continue
                val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
                if (parts.size != 4) continue
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                for (i in 1..254) {
                    val candidate = "$prefix.$i"
                    if (candidate != ip) hosts += candidate
                    if (hosts.size >= MAX_HOSTS) return hosts.toList()
                }
            }
        }
        return hosts.toList()
    }

    private fun probe(host: String): List<DiscoveredServer> {
        val hits = ArrayList<DiscoveredServer>(2)
        if (portOpen(host, 445)) {
            hits += DiscoveredServer(ServerType.SMB, host, 445, host)
        }
        if (portOpen(host, 21)) {
            hits += DiscoveredServer(ServerType.FTP, host, 21, host)
        }
        if (portOpen(host, 80) && isWebDav(host, 80)) {
            hits += DiscoveredServer(ServerType.WEBDAV, host, 80, host)
        }
        if (portOpen(host, 8080) && isWebDav(host, 8080)) {
            hits += DiscoveredServer(ServerType.WEBDAV, host, 8080, host)
        }
        return hits
    }

    private fun portOpen(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun isWebDav(host: String, port: Int): Boolean = try {
        val request = Request.Builder()
            .url("http://$host:$port/")
            .method("OPTIONS", null)
            .build()
        webDavClient.newCall(request).execute().use { response ->
            response.header("DAV") != null ||
                response.header("Allow")?.contains("PROPFIND", ignoreCase = true) == true
        }
    } catch (_: Exception) {
        false
    }
}
