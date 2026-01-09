package link.yggdrasil.yggstack.android.ui.configuration.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import link.yggdrasil.yggstack.android.data.ConfigRepository
import link.yggdrasil.yggstack.android.data.PeerListForIp
import link.yggdrasil.yggstack.android.data.PublicPeerInfo
import link.yggdrasil.yggstack.android.data.PublicPeersCache
import link.yggdrasil.yggstack.android.service.PeerFetcherService
import link.yggdrasil.yggstack.android.service.PeerPingerService

/**
 * ViewModel for Peer Discovery screen
 */
class PeerDiscoveryViewModel(
    private val repository: ConfigRepository
) : ViewModel() {

    private val peerFetcher = PeerFetcherService()
    private val peerPinger = PeerPingerService()

    private val _externalIp = MutableStateFlow<String?>(null)
    val externalIp: StateFlow<String?> = _externalIp.asStateFlow()

    private val _peers = MutableStateFlow<List<PublicPeerInfo>>(emptyList())
    val peers: StateFlow<List<PublicPeerInfo>> = _peers.asStateFlow()

    private val _selectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val selectedPeers: StateFlow<Set<String>> = _selectedPeers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sortType = MutableStateFlow<String?>(null)
    val sortType: StateFlow<String?> = _sortType.asStateFlow()

    /**
     * Refresh external IP and load list for current network
     */
    fun refreshExternalIp() {
        viewModelScope.launch {
            try {
                // Get external IP
                val ipResult = peerFetcher.getExternalIp()
                if (ipResult.isSuccess) {
                    val ip = ipResult.getOrNull()
                    _externalIp.value = ip
                    
                    // Load data for current IP (always, even if IP didn't change)
                    if (ip != null) {
                        loadPeersForCurrentIp(ip)
                    }
                }
                
                // Sync selected peers with config
                val config = repository.configFlow.first()
                val peersInConfig = config.peers.toSet()
                _selectedPeers.value = peersInConfig
                
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
            }
        }
    }
    
    /**
     * Load peers for current IP from cache or create unsorted list
     */
    private suspend fun loadPeersForCurrentIp(ip: String) {
        // Get global cache
        val globalCache = repository.getPublicPeersCache()
        
        if (globalCache.peers.isEmpty()) {
            // No global cache - show empty
            _peers.value = emptyList()
            _sortType.value = null
            return
        }
        
        // Check if we have sorted list for this IP
        val sortedList = repository.getSortedListForIp(ip)
        
        if (sortedList != null) {
            // Use sorted list with RTT data for this IP
            _peers.value = sortedList.peers
            _sortType.value = sortedList.sortType
        } else {
            // No sorted list - show unsorted from global cache (without RTT)
            _peers.value = globalCache.peers
            _sortType.value = "unsorted"
            
            // Save as unsorted for this IP
            repository.saveSortedListForIp(
                ip,
                PeerListForIp(
                    peers = globalCache.peers,
                    sortedAt = System.currentTimeMillis(),
                    sortType = "unsorted"
                )
            )
        }
    }

    /**
     * Fetch public peers from remote source (update global cache)
     */
    fun fetchPeers() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _loadingMessage.value = "Downloading peers list..."
                _errorMessage.value = null
                _progress.value = 0f

                val result = peerFetcher.fetchPublicPeers()
                
                if (result.isSuccess) {
                    val fetchedPeers = result.getOrNull() ?: emptyList()
                    
                    // Update global cache
                    repository.savePublicPeersCache(
                        PublicPeersCache(
                            peers = fetchedPeers,
                            downloadedAt = System.currentTimeMillis()
                        )
                    )
                    
                    // Clear all sorted lists (they're now outdated)
                    repository.clearAllSortedLists()
                    
                    // Show unsorted list
                    _peers.value = fetchedPeers
                    _sortType.value = "unsorted"
                    
                    // Save unsorted list for current IP
                    val ip = _externalIp.value
                    if (ip != null) {
                        repository.saveSortedListForIp(
                            ip,
                            PeerListForIp(
                                peers = fetchedPeers,
                                sortedAt = System.currentTimeMillis(),
                                sortType = "unsorted"
                            )
                        )
                    }
                    
                    _errorMessage.value = null
                } else {
                    // Failed to download - show error, keep current data
                    _errorMessage.value = "Peers list download failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Peers list download failed: ${e.message}"
            } finally {
                _isLoading.value = false
                _loadingMessage.value = ""
                _progress.value = 0f
            }
        }
    }

    /**
     * Sort peers by RTT (protocol connect check)
     */
    fun sortByRTT() {
        viewModelScope.launch {
            try {
                val currentPeers = _peers.value
                if (currentPeers.isEmpty()) {
                    _errorMessage.value = "No peers to sort. Fetch peers first."
                    return@launch
                }

                _isLoading.value = true
                _loadingMessage.value = "Resolving hostnames..."
                _errorMessage.value = null
                _progress.value = 0f

                // Sort by checking unique hosts
                val sortedPeers = peerPinger.checkPeersByHostWithProgress(currentPeers) { checked, total ->
                    if (checked < 0) {
                        // DNS resolution phase (negative values)
                        val resolved = -checked
                        _progress.value = resolved.toFloat() / total.toFloat()
                        _loadingMessage.value = "Resolving hostnames: $resolved/$total"
                    } else {
                        // RTT check phase (positive values)
                        _progress.value = checked.toFloat() / total.toFloat()
                        _loadingMessage.value = "Checking peers: $checked/$total"
                    }
                }

                _peers.value = sortedPeers
                _sortType.value = "connect"

                // Save sorted peers with RTT data for current IP
                val ip = _externalIp.value
                if (ip != null) {
                    repository.saveSortedListForIp(
                        ip,
                        PeerListForIp(
                            peers = sortedPeers,  // Store full peers with RTT for this IP
                            sortedAt = System.currentTimeMillis(),
                            sortType = "connect"
                        )
                    )
                }

                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Error during RTT check: ${e.message}"
            } finally {
                _isLoading.value = false
                _loadingMessage.value = ""
                _progress.value = 0f
            }
        }
    }

    /**
     * Toggle peer selection and sync with config
     */
    suspend fun togglePeerSelection(uri: String) {
        val config = repository.configFlow.first()
        val isInConfig = uri in config.peers
        
        if (isInConfig) {
            // Remove from config and selection
            val updatedConfig = config.copy(
                peers = config.peers - uri
            )
            repository.saveConfig(updatedConfig)
            _selectedPeers.value = _selectedPeers.value - uri
        } else {
            // Add to config and selection
            val updatedConfig = config.copy(
                peers = config.peers + uri
            )
            repository.saveConfig(updatedConfig)
            _selectedPeers.value = _selectedPeers.value + uri
        }
    }

    /**
     * Get display list - returns peers in original sort order
     */
    fun getDisplayPeers(): StateFlow<List<PublicPeerInfo>> = _peers.asStateFlow()

    /**
     * Clear error message
     */
    fun clearError() {
        _errorMessage.value = null
    }

    class Factory(private val repository: ConfigRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PeerDiscoveryViewModel::class.java)) {
                return PeerDiscoveryViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
