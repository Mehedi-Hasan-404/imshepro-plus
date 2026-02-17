package com.livetvpro.ui.categories

import android.app.Application
import android.net.Uri
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
import com.livetvpro.utils.AndroidRetryViewModel
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
    application: Application,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository,
    private val savedStateHandle: SavedStateHandle
) : AndroidRetryViewModel(application) {

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedGroup = MutableStateFlow("All")
    
    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())
    
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    private val _categoryGroups = MutableLiveData<List<String>>(emptyList())
    val categoryGroups: LiveData<List<String>> = _categoryGroups
    
    private val _currentGroup = MutableLiveData<String>("All")
    val currentGroup: LiveData<String> = _currentGroup
    
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

    override fun loadData() {
        lastLoadedCategoryId?.let { loadChannels(it) }
    }

    // FIXED: Override onResume() instead to prevent auto-reload when coming back from player
    // This prevents unnecessary reloading that would lose your scroll position and reload data
    override fun onResume() {
        // Do nothing - don't reload data when resuming
        // Data is already loaded and cached in memory
    }

    fun loadChannels(categoryId: String) {
        viewModelScope.launch {
            startLoading()
            lastLoadedCategoryId = categoryId
            
            try {
                val playlist = playlistRepository.getPlaylistById(categoryId)
                
                if (playlist != null) {
                    loadPlaylistChannels(playlist)
                } else {
                    val channels = channelRepository.getChannelsByCategory(categoryId)
                    _channels.value = channels
                    extractAndSetGroups(channels)
                    
                    finishLoading(dataIsEmpty = channels.isEmpty())
                }
            } catch (e: Exception) {
                _channels.value = emptyList()
                
                finishLoading(dataIsEmpty = true, error = e)
            }
        }
    }

    private suspend fun loadPlaylistChannels(playlist: com.livetvpro.data.models.Playlist) {
        try {
            val playlistSource = if (playlist.isFile) playlist.filePath else playlist.url
            val channels = if (playlistSource.startsWith("content://") || playlistSource.startsWith("file://")) {
                val uri = Uri.parse(playlistSource)
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val m3uChannels = M3uParser.parseM3uContent(content)
                M3uParser.convertToChannels(m3uChannels, playlist.id, playlist.title)
            } else {
                withContext(Dispatchers.IO) {
                    val client = OkHttpClient()
                    val request = Request.Builder().url(playlistSource).build()
                    val response = client.newCall(request).execute()
                    val content = response.body?.string() ?: ""
                    val m3uChannels = M3uParser.parseM3uContent(content)
                    M3uParser.convertToChannels(m3uChannels, playlist.id, playlist.title)
                }
            }
            
            _channels.value = channels
            extractAndSetGroups(channels)
            
            finishLoading(dataIsEmpty = channels.isEmpty())
        } catch (e: Exception) {
            _channels.value = emptyList()
            
            finishLoading(dataIsEmpty = true, error = e)
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
        return channel.groupTitle?.takeIf { it.isNotBlank() }
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
}
