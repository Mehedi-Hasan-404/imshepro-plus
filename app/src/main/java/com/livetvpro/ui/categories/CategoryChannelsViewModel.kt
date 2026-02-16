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
import com.livetvpro.utils.ErrorMessageConverter
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
    
    // Lifecycle tracking
    private var hasLoadedOnce = false
    private var lastLoadedCategoryId: String? = null

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
            _error.value = null
            lastLoadedCategoryId = categoryId
            
            try {
                // Check if it's a playlist ID
                val playlist = playlistRepository.getPlaylistById(categoryId)
                
                if (playlist != null) {
                    // It's a playlist - load playlist channels
                    loadPlaylistChannels(playlist)
                } else {
                    // It's a regular category
                    val channels = channelRepository.getChannelsByCategory(categoryId)
                    _channels.value = channels
                    extractAndSetGroups(channels)
                    
                    // Only show error if we haven't loaded data before
                    if (channels.isEmpty() && !hasLoadedOnce) {
                        _error.value = "No channels"
                    } else {
                        if (channels.isNotEmpty()) {
                            hasLoadedOnce = true
                        }
                        _error.value = null
                    }
                }
            } catch (e: Exception) {
                // Only show error if we haven't loaded before
                if (!hasLoadedOnce) {
                    _error.value = ErrorMessageConverter.getShortErrorMessage(e)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadPlaylistChannels(playlist: com.livetvpro.data.models.Playlist) {
        try {
            val playlistSource = if (playlist.isFile) playlist.filePath else playlist.url
            val channels = if (playlistSource.startsWith("content://") || playlistSource.startsWith("file://")) {
                // Local file
                val uri = Uri.parse(playlistSource)
                val inputStream = application.contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                M3uParser.parse(content, playlist.title)
            } else {
                // Remote URL
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(playlistSource).build()
                    val response = client.newCall(request).execute()
                    val content = response.body?.string() ?: ""
                    M3uParser.parse(content, playlist.title)
                }
            }
            
            _channels.value = channels
            extractAndSetGroups(channels)
            
            // Only show error if we haven't loaded data before
            if (channels.isEmpty() && !hasLoadedOnce) {
                _error.value = "No channels"
            } else {
                if (channels.isNotEmpty()) {
                    hasLoadedOnce = true
                }
                _error.value = null
            }
        } catch (e: Exception) {
            // Only show error if we haven't loaded before
            if (!hasLoadedOnce) {
                _error.value = ErrorMessageConverter.getShortErrorMessage(e)
            }
        }
    }

    private fun extractAndSetGroups(channels: List<Channel>) {
        val groupsSet = channels
            .mapNotNull { extractGroupFromChannel(it) }
            .toSet()
            .sorted()
        
        val groupsList = if (groupsSet.isNotEmpty()) {
            listOf("All") + groupsSet
        } else {
            emptyList()
        }
        
        _categoryGroups.value = groupsList
    }

    private fun extractGroupFromChannel(channel: Channel): String? {
        return channel.group?.takeIf { it.isNotBlank() }
    }

    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    fun selectGroup(group: String) {
        _selectedGroup.value = group
        _currentGroup.value = group
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            val favoriteLinks = channel.links?.map { channelLink ->
                ChannelLink(
                    quality = channelLink.quality,
                    url = channelLink.url,
                    cookie = channelLink.cookie,
                    referer = channelLink.referer,
                    origin = channelLink.origin,
                    userAgent = channelLink.userAgent,
                    drmScheme = channelLink.drmScheme,
                    drmLicenseUrl = channelLink.drmLicenseUrl
                )
            }
            
            val streamUrlToSave = when {
                channel.streamUrl.isNotEmpty() -> channel.streamUrl
                !favoriteLinks.isNullOrEmpty() -> {
                    val firstLink = favoriteLinks.first()
                    buildStreamUrlFromLink(firstLink)
                }
                else -> ""
            }
            
            val favoriteChannel = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = streamUrlToSave,
                categoryId = channel.categoryId,
                categoryName = categoryName,
                links = favoriteLinks
            )
            
            if (favoritesRepository.isFavorite(channel.id)) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(favoriteChannel)
            }
        }
    }
    
    private fun buildStreamUrlFromLink(link: ChannelLink): String {
        val parts = mutableListOf<String>()
        parts.add(link.url)
        
        link.referer?.let { if (it.isNotEmpty()) parts.add("referer=$it") }
        link.cookie?.let { if (it.isNotEmpty()) parts.add("cookie=$it") }
        link.origin?.let { if (it.isNotEmpty()) parts.add("origin=$it") }
        link.userAgent?.let { if (it.isNotEmpty()) parts.add("User-Agent=$it") }
        link.drmScheme?.let { if (it.isNotEmpty()) parts.add("drmScheme=$it") }
        link.drmLicenseUrl?.let { if (it.isNotEmpty()) parts.add("drmLicense=$it") }
        
        return if (parts.size > 1) {
            parts.joinToString("|")
        } else {
            parts[0]
        }
    }

    fun isFavorite(channelId: String): Boolean {
        return _favoriteStatusCache.value.contains(channelId)
    }

    fun retry() {
        hasLoadedOnce = false  // Reset to allow error display
        _error.value = null
        lastLoadedCategoryId?.let { loadChannels(it) }
    }
    
    fun onResume() {
        // Refresh data if already loaded once
        if (hasLoadedOnce) {
            lastLoadedCategoryId?.let { loadChannels(it) }
        }
    }
}
