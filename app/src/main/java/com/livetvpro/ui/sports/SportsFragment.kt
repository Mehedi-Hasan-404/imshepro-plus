package com.livetvpro.ui.sports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ChannelLink
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.FavoritesRepository
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: CategoryRepository,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private val _channels = MutableLiveData<List<Channel>>()
    private val _filteredChannels = MutableLiveData<List<Channel>>()
    val filteredChannels: LiveData<List<Channel>> = _filteredChannels

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())

    private var currentQuery: String = ""

    init {
        loadSports()
        loadFavoriteCache()
    }

    private fun loadFavoriteCache() {
        viewModelScope.launch {
            favoritesRepository.getFavoritesFlow().collect { favorites ->
                _favoriteStatusCache.value = favorites.map { it.id }.toSet()
            }
        }
    }

    fun loadSports() {
        viewModelScope.launch {
            repository.getSports()
                .onStart {
                    _isLoading.value = true
                    _error.value = null
                }
                .catch { e ->
                    _isLoading.value = false
                    _error.value = e.message ?: "Failed to load sports"
                }
                .collect { sports ->
                    _isLoading.value = false
                    _channels.value = sports
                    applyFilter()
                }
        }
    }

    fun searchSports(query: String) {
        currentQuery = query
        applyFilter()
    }

    private fun applyFilter() {
        val channels = _channels.value ?: emptyList()
        
        if (currentQuery.isBlank()) {
            _filteredChannels.value = channels
        } else {
            _filteredChannels.value = channels.filter { channel ->
                channel.name.contains(currentQuery, ignoreCase = true)
            }
        }
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
                categoryName = "Sports",
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
        loadSports()
    }
}

@AndroidEntryPoint
class SportsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SportsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    @Inject
    lateinit var listenerManager: NativeListenerManager

    override fun onSearchQuery(query: String) {
        viewModel.searchSports(query)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val shouldBlock = listenerManager.onPageInteraction(
                    pageType = ListenerConfig.PAGE_LIVE_EVENTS,
                    uniqueId = channel.id
                )
                
                if (shouldBlock) {
                    return@ChannelAdapter
                }
                
                if (channel.links != null && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)
                } else {
                    PlayerActivity.startWithChannel(requireContext(), channel)
                }
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                kotlinx.coroutines.MainScope().launch {
                    kotlinx.coroutines.delay(50)
                    if (_binding != null) {
                        channelAdapter.refreshItem(channel.id)
                    }
                }
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        val spanCount = resources.getInteger(com.livetvpro.R.integer.grid_column_count)
        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = channelAdapter
            setHasFixedSize(true)
        }
    }

    private fun showLinkSelectionDialog(channel: Channel) {
        val links = channel.links ?: return
        val linkLabels = links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream Quality")
            .setItems(linkLabels) { dialog, which ->
                PlayerActivity.startWithChannel(requireContext(), channel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)
            binding.emptyView.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewChannels.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
                binding.recyclerViewChannels.visibility = View.GONE
            } else {
                binding.errorView.visibility = View.GONE
                binding.recyclerViewChannels.visibility = View.VISIBLE
            }
        }
        
        binding.retryButton.setOnClickListener {
            viewModel.retry()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
