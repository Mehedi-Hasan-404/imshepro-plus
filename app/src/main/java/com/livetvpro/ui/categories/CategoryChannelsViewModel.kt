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
    
    // Cache for favorite status to avoid repeated DB queries
    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())
    
    val categoryName: String = savedStateHandle.get<String>("categoryName") ?: "Channels"

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    // List of available category groups extracted from channels
    private val _categoryGroups = MutableLiveData<List<String>>(emptyList())
    val categoryGroups: LiveData<List<String>> = _categoryGroups
    
    // Currently selected group
    private val _currentGroup = MutableLiveData<String>("All")
    val currentGroup: LiveData<String> = _currentGroup

    val filteredChannels: LiveData<List<Channel>> = combine(
        _channels, 
        _searchQuery, 
        _selectedGroup
    ) { list, query, group ->
        var filtered = list
        
        // Filter by group
        if (group != "All") {
            filtered = filtered.filter { channel ->
                extractGroupFromChannel(channel) == group
            }
        }
        
        // Filter by search query
        if (query.isNotEmpty()) {
            filtered = filtered.filter { it.name.contains(query, ignoreCase = true) }
        }
        
        filtered
    }.asLiveData(viewModelScope.coroutineContext + Dispatchers.Main)

    init {
        // Load favorite status cache
        loadFavoriteCache()
    }

    /**
     * Load all favorite IDs into cache
     */
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
                
                // Extract unique groups from channels
                extractCategoryGroups(result)
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    /**
     * Extract category groups from channels based on group-title attribute
     */
    private fun extractCategoryGroups(channels: List<Channel>) {
        val groups = mutableSetOf<String>()
        
        channels.forEach { channel ->
            val group = extractGroupFromChannel(channel)
            if (group.isNotEmpty()) {
                groups.add(group)
            }
        }
        
        // Only add "All" and show groups if there are actual group titles
        // If groups is empty, it means no channels have group-title attributes
        val groupList = if (groups.isNotEmpty()) {
            mutableListOf("All").apply {
                addAll(groups.sorted())
            }
        } else {
            emptyList()
        }
        
        _categoryGroups.value = groupList
    }
    
    /**
     * Extract group name from channel
     * Uses the groupTitle parsed from M3U group-title attribute
     */
    private fun extractGroupFromChannel(channel: Channel): String {
        // Use the groupTitle from M3U parsing
        return channel.groupTitle.ifEmpty { 
            // Fallback to categoryName if groupTitle is not available
            channel.categoryName 
        }
    }
    
    /**
     * Select a category group for filtering
     */
    fun selectGroup(group: String) {
        _selectedGroup.value = group
        _currentGroup.value = group
    }

    fun searchChannels(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch {
            // Convert Channel.links to FavoriteChannel.links
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
                links = favoriteLinks // Pass links to favorite
            )
            
            if (favoritesRepository.isFavorite(channel.id)) {
                favoritesRepository.removeFavorite(channel.id)
            } else {
                favoritesRepository.addFavorite(favoriteChannel)
            }
            
            // Cache will update automatically via Flow
        }
    }

    /**
     * Check if a channel is favorited using the cache
     * This is called synchronously from the adapter
     */
    fun isFavorite(channelId: String): Boolean {
        return _favoriteStatusCache.value.contains(channelId)
    }
}
