package link.yggdrasil.yggstack.android.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import link.yggdrasil.yggstack.android.data.PublicPeerInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Service for checking peer availability via TCP connect
 */
class PeerPingerService {
    
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_CONCURRENT_CHECKS = 10
        
        // Protocol priority for checking (from most reliable to least)
        private val PROTOCOL_PRIORITY = listOf("tcp", "tls", "ws", "wss", "quic")
    }

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /**
     * Check a single peer via TCP connect and measure RTT
     */
    suspend fun checkPeer(peer: PublicPeerInfo): PublicPeerInfo = withContext(Dispatchers.IO) {
        try {
            val (host, port) = parseUri(peer.uri)
            
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val rtt = System.currentTimeMillis() - startTime
                
                peer.copy(
                    rtt = rtt,
                    lastChecked = System.currentTimeMillis()
                )
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            // Connection failed - return peer without RTT
            peer.copy(
                rtt = null,
                lastChecked = System.currentTimeMillis()
            )
        }
    }

    /**
     * Check multiple peers in parallel with limited concurrency
     * Returns list sorted by RTT (fastest first)
     */
    suspend fun checkPeersWithProgress(
        peers: List<PublicPeerInfo>,
        onProgress: (checked: Int, total: Int) -> Unit
    ): List<PublicPeerInfo> = withContext(Dispatchers.IO) {
        if (peers.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<PublicPeerInfo>()
        val channel = Channel<PublicPeerInfo>(MAX_CONCURRENT_CHECKS)
        var checkedCount = 0

        // Launch workers
        val workers = List(MAX_CONCURRENT_CHECKS) {
            launch {
                for (peer in channel) {
                    val result = checkPeer(peer)
                    synchronized(results) {
                        results.add(result)
                        checkedCount++
                        onProgress(checkedCount, peers.size)
                    }
                }
            }
        }

        // Send peers to channel
        launch {
            peers.forEach { channel.send(it) }
            channel.close()
        }

        // Wait for all workers to complete
        workers.forEach { it.join() }

        // Sort by RTT: peers with RTT first (sorted by value), then peers without RTT
        results.sortedWith(compareBy(
            { it.rtt == null },  // nulls last
            { it.rtt }           // sort by RTT value
        ))
    }

    /**
     * Group peers by IP to avoid duplicate checks on same host
     */
    suspend fun groupPeersByHost(peers: List<PublicPeerInfo>): Map<String, List<PublicPeerInfo>> {
        return peers.groupBy { peer ->
            try {
                val (host, _) = parseUri(peer.uri)
                host
            } catch (e: Exception) {
                peer.uri // fallback to full URI
            }
        }
    }

    /**
     * Resolve hostname to IP address
     * Returns the IP address as string, or null if resolution fails
     */
    private suspend fun resolveHostToIp(host: String): String? = withContext(Dispatchers.IO) {
        try {
            // Check if already an IP address
            if (host.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                return@withContext host
            }
            // IPv6 check (simplified)
            if (host.contains(":")) {
                return@withContext host
            }
            
            // Resolve DNS
            val address = InetAddress.getByName(host)
            address.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Group peers by resolved IP address with progress callback
     * DNS names pointing to same IP will be grouped together
     */
    private suspend fun groupPeersByIp(
        peers: List<PublicPeerInfo>,
        onProgress: ((resolved: Int, total: Int) -> Unit)? = null
    ): Map<String, List<PublicPeerInfo>> = withContext(Dispatchers.IO) {
        val groups = mutableMapOf<String, MutableList<PublicPeerInfo>>()
        val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val total = peers.size
        
        // Resolve DNS in parallel with limited concurrency
        val channel = Channel<Pair<PublicPeerInfo, String>>(MAX_CONCURRENT_CHECKS)
        
        // Launch workers for DNS resolution
        val workers = List(MAX_CONCURRENT_CHECKS) {
            launch {
                for ((peer, _) in channel) {
                    try {
                        val (host, _) = parseUri(peer.uri)
                        val ip = resolveHostToIp(host) ?: host
                        
                        synchronized(groups) {
                            groups.getOrPut(ip) { mutableListOf() }.add(peer)
                        }
                    } catch (e: Exception) {
                        synchronized(groups) {
                            groups.getOrPut(peer.uri) { mutableListOf() }.add(peer)
                        }
                    }
                    
                    val count = resolvedCount.incrementAndGet()
                    onProgress?.invoke(count, total)
                }
            }
        }
        
        // Send peers to workers
        launch {
            peers.forEach { peer ->
                channel.send(peer to "")
            }
            channel.close()
        }
        
        // Wait for all workers
        workers.forEach { it.join() }
        
        groups
    }

    /**
     * Extract protocol from URI (tcp, tls, ws, wss, quic, etc.)
     */
    private fun extractProtocol(uriString: String): String? {
        return try {
            val cleanUri = uriString.split("?")[0]
            val uri = URI(cleanUri)
            uri.scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Select best representative peer from a group based on protocol priority
     * Priority: TCP > TLS > WS > WSS > QUIC > others
     */
    private fun selectRepresentativePeer(peers: List<PublicPeerInfo>): PublicPeerInfo {
        // Try to find peer by protocol priority
        for (protocol in PROTOCOL_PRIORITY) {
            val peer = peers.find { extractProtocol(it.uri) == protocol }
            if (peer != null) return peer
        }
        
        // Fallback to first peer if no priority match
        return peers.first()
    }

    /**
     * Check a peer by trying multiple protocols in priority order
     * Returns the peer with RTT from first successful protocol check
     */
    private suspend fun checkPeerWithProtocolFallback(ipPeers: List<PublicPeerInfo>): PublicPeerInfo? = withContext(Dispatchers.IO) {
        // Group peers by protocol for this IP
        val peersByProtocol = ipPeers.groupBy { extractProtocol(it.uri) }
        
        // Try protocols in priority order
        for (protocol in PROTOCOL_PRIORITY) {
            val protocolPeers = peersByProtocol[protocol]
            if (protocolPeers.isNullOrEmpty()) continue
            
            // Try first peer of this protocol
            val peer = protocolPeers.first()
            val checked = checkPeer(peer)
            
            if (checked.rtt != null) {
                // Success! Return this peer with RTT
                return@withContext checked
            }
        }
        
        // No protocol succeeded, check first available peer to get null RTT
        val firstPeer = ipPeers.first()
        checkPeer(firstPeer)
    }

    /**
     * Check unique hosts and apply RTT to all peers on same host
     */
    suspend fun checkPeersByHostWithProgress(
        peers: List<PublicPeerInfo>,
        onProgress: (checked: Int, total: Int) -> Unit,
        onIncrementalUpdate: ((List<PublicPeerInfo>) -> Unit)? = null
    ): List<PublicPeerInfo> = withContext(Dispatchers.IO) {
        // Group peers by resolved IP address (with progress for DNS resolution)
        val peersByIp = groupPeersByIp(peers) { resolved, total ->
            // Show DNS resolution progress: "Resolving: X/Y"
            onProgress(-resolved, total) // Negative to indicate DNS phase
        }
        val uniqueIps = peersByIp.keys.toList()
        
        if (uniqueIps.isEmpty()) return@withContext emptyList()
        
        // Check each IP group with protocol fallback
        val results = mutableListOf<PublicPeerInfo>()
        val channel = Channel<Pair<String, List<PublicPeerInfo>>>(MAX_CONCURRENT_CHECKS)
        var checkedCount = 0
        
        // Launch workers
        val workers = List(MAX_CONCURRENT_CHECKS) {
            launch {
                for ((ip, ipPeers) in channel) {
                    val checkedPeer = checkPeerWithProtocolFallback(ipPeers)
                    
                    val updatedList = synchronized(results) {
                        if (checkedPeer != null) {
                            // Apply RTT to all peers with this IP
                            val rtt = checkedPeer.rtt
                            ipPeers.forEach { peer ->
                                results.add(peer.copy(
                                    rtt = rtt,
                                    lastChecked = System.currentTimeMillis()
                                ))
                            }
                        } else {
                            // Shouldn't happen, but handle gracefully
                            ipPeers.forEach { peer ->
                                results.add(peer.copy(
                                    rtt = null,
                                    lastChecked = System.currentTimeMillis()
                                ))
                            }
                        }
                        
                        checkedCount++
                        onProgress(checkedCount, uniqueIps.size)
                        
                        // Return sorted snapshot for incremental update
                        results.sortedWith(compareBy(
                            { it.rtt == null },
                            { it.rtt }
                        ))
                    }
                    
                    // Send incremental update
                    onIncrementalUpdate?.invoke(updatedList)
                }
            }
        }
        
        // Send IP groups to channel
        launch {
            peersByIp.forEach { (ip, ipPeers) ->
                channel.send(ip to ipPeers)
            }
            channel.close()
        }
        
        // Wait for all workers to complete
        workers.forEach { it.join() }
        
        // Sort results by RTT
        results.sortedWith(compareBy(
            { it.rtt == null },
            { it.rtt }
        ))
    }

    /**
     * Parse peer URI to extract host and port
     * Handles: tcp://host:port, tls://host:port, quic://host:port, etc.
     */
    private fun parseUri(uriString: String): Pair<String, Int> {
        try {
            // Handle URIs with query parameters (e.g., ?key=...)
            val cleanUri = uriString.split("?")[0]
            
            val uri = URI(cleanUri)
            var host = uri.host ?: throw IllegalArgumentException("No host in URI")
            val port = uri.port
            
            if (port <= 0) {
                throw IllegalArgumentException("Invalid port in URI")
            }
            
            // Remove IPv6 brackets if present
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            
            return Pair(host, port)
        } catch (e: Exception) {
            // Fallback: try to parse manually
            val regex = Regex("""^[a-z]+://\[?([^\]]+)\]?:(\d+)""")
            val match = regex.find(uriString)
            if (match != null) {
                val host = match.groupValues[1]
                val port = match.groupValues[2].toInt()
                return Pair(host, port)
            }
            throw IllegalArgumentException("Cannot parse URI: $uriString", e)
        }
    }
}
