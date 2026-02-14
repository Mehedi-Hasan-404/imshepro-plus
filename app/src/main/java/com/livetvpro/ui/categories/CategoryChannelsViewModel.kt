package com.livetvpro.ui.categories

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import com.livetvpro.data.repository.PlaylistRepository
import com.livetvpro.utils.M3uParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class CategoryChannelsViewModel @Inject constructor(
    private val application: Application,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedGroup = MutableStateFlow("All")
    
    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())
    
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _categoryGroups = MutableLiveData<List<String>>(emptyList())
    val categoryGroups: LiveData<List<String>> = _categoryGroups
    
    private val _currentGroup = MutableLiveData<String>("All")
    val currentGroup: LiveData<String> = _currentGroup

    val filteredChannels: LiveData<List<Channel>> = combine(
        _channels, 
        _searchQuery, 
        _selectedGroup
    ) { list, query, group ->
        var filtered = list
        
        if (group != "All") {
            filtered = filtered.filter { channel ->
                extractGroupFromChannel(channel) == group
            }
        }
        
        if (query.isNotEmpty()) {
            filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        }
        
        filtered
    }.asLiveData(viewModelScope.coroutineContext + Dispatchers.Main)

    init {
        loadFavoriteCache()
    }

    private fun loadFavoriteCache() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favorites ->
                _favoriteStatusCache.value = favorites.map { it.id }.toSet()
            }
        }
    }

    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Check if it's a playlist ID
                val playlist = playlistRepository.getPlaylistById(categoryId)
                
                if (playlist != null) {
                    // It's a playlist - load playlist channels
                    loadPlaylistChannels(playlist)
                } else {
                    // It's a regular category - use existing logic
                    loadCategoryChannels(categoryId)
                }
            } catch (e: Exception) {
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun loadPlaylistChannels(playlist: com.livetvpro.data.models.Playlist) {
        try {
            // Get the M3U content
            val m3uContent = if (playlist.isFile) {
                // Read from file URI
                readFileContent(playlist.filePath)
            } else {
                // Fetch from URL
                fetchUrlContent(playlist.url)
            }

            // Parse M3U content using your existing M3uParser
            val channels = M3uParser.parseM3uContent(m3uContent)
            
            _channels.value = channels
            
            // Extract unique groups for the tab layout
            extractCategoryGroups(channels)
            
        } catch (e: Exception) {
            _error.value = "Failed to load playlist: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun readFileContent(uriString: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                application.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: throw Exception("Could not read file")
            } catch (e: Exception) {
                throw Exception("Failed to read file: ${e.message}")
            }
        }
    }
    
    private suspend fun fetchUrlContent(url: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                    
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to fetch playlist: HTTP ${response.code}")
                    }
                    response.body?.string() ?: throw Exception("Empty response")
                }
            } catch (e: Exception) {
                throw Exception("Failed to fetch URL: ${e.message}")
            }
        }
    }
    
    private suspend fun loadCategoryChannels(categoryId: String) {
        try {
            val result = channelRepository.getChannelsByCategory(categoryId)
            _channels.value = result
            
            extractCategoryGroups(result)
        } finally {
            _isLoading.value = false
        }
    }
    
    private fun extractCategoryGroups(channels: List<Channel>) {
        val groups = mutableSetOf<String>()
        
        channels.forEach { channel ->
            val group = extractGroupFromChannel(channel)
            if (group.isNotEmpty()) {
                groups.add(group)
            }
        }
        
        val groupList = if (groups.isNotEmpty()) {
            mutableListOf("All").apply {
                addAll(groups.sorted())
            }
        } else {
            emptyList()
        }
        
        _categoryGroups.value = groupList
    }
    
    private fun extractGroupFromChannel(channel: Channel): String {
        return channel.groupTitle
    }
    
    fun selectGroup(group: String) {
        _selectedGroup.value = group
        _currentGroup.value = group
    }

    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val favoriteLinks = channel.links?.map { channelLink ->
                ChannelLink(
                    quality = channelLink.quality,
                    url = channelLink.url
                )
            }
            
            val favoriteChannel = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = channel.streamUrl,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName,
                links = favoriteLinks
            )
            
            if (favoritesRepository.isFavorite(channel.id)) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(favoriteChannel)
            }
        }
    }

    fun isFavorite(channelId: String): Boolean {
        return _favoriteStatusCache.value.contains(channelId)
    }
}
