package com.livetvpro.ui.sports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import com.livetvpro.utils.RetryViewModel
import com.livetvpro.utils.RetryHandler
import com.livetvpro.utils.Refreshable
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
) : RetryViewModel() {

    private val _channels = MutableLiveData<List<Channel>>()
    private val _filteredChannels = MutableLiveData<List<Channel>>()
    val filteredChannels: LiveData<List<Channel>> = _filteredChannels

    private val _favoriteStatusCache = MutableStateFlow<Set<String>>(emptySet())

    private var currentQuery: String = ""

    init {
        loadData()
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
        viewModelScope.launch {
            repository.getSports()
                .onStart {
                    startLoading()
                }
                .catch { e ->
                    _channels.value = emptyList()
                    applyFilter()
                    finishLoading(dataIsEmpty = true, error = e)
                }
                .collect { sports ->
                    _channels.value = sports
                    applyFilter()
                    finishLoading(dataIsEmpty = sports.isEmpty())
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
}

@AndroidEntryPoint
class SportsFragment : Fragment(), SearchableFragment, Refreshable {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SportsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    @Inject
    lateinit var listenerManager: NativeListenerManager

    override fun onSearchQuery(query: String) {
        viewModel.searchSports(query)
    }
    
    override fun refreshData() {
        viewModel.refresh()
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val columnCount = resources.getInteger(com.livetvpro.R.integer.grid_column_count)
        (binding.recyclerViewChannels.layoutManager as? GridLayoutManager)?.spanCount = columnCount
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupRetryHandling()
    }
    
    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val shouldBlock = listenerManager.onPageInteraction(
                    pageType = ListenerConfig.PAGE_SPORTS,
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
            .setTitle("Multiple Links Available")
            .setItems(linkLabels) { dialog, which ->
                PlayerActivity.startWithChannel(requireContext(), channel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRetryHandling() {
        RetryHandler.setupGlobal(
            lifecycleOwner = viewLifecycleOwner,
            viewModel = viewModel,
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            contentView = binding.recyclerViewChannels,
            swipeRefresh = binding.swipeRefresh,
            progressBar = binding.progressBar,
            emptyView = binding.emptyView
        )

        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
