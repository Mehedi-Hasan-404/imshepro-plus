package com.livetvpro.ui.categories

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryChannelsViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

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
                val result = channelRepository.getChannelsByCategory(categoryId)
                _channels.value = result
                
                extractCategoryGroups(result)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
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
        
        val sortedGroups = groups.sorted().toMutableList()
        sortedGroups.add(0, "All")
        _categoryGroups.value = sortedGroups
    }
    
    private fun extractGroupFromChannel(channel: Channel): String {
        return when {
            channel.groupTitle.isNotEmpty() -> channel.groupTitle
            else -> ""
        }
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
