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
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {

    private val _channels = MutableLiveData<List<Channel>>()
    private val _filteredChannels = MutableLiveData<List<Channel>>()
    val filteredChannels: LiveData<List<Channel>> = _filteredChannels

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentQuery: String = ""

    init {
        loadSports()
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
        setupSwipeRefresh()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_LIVE_EVENTS)
                if (shouldBlock) return@ChannelAdapter
                
                // Show link selection dialog if multiple links exist
                if (channel.links != null && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)
                } else {
                    // Single link or no links - go directly to player
                    PlayerActivity.startWithChannel(requireContext(), channel)
                }
            },
            onFavoriteToggle = { /* Sports don't support favorites */ },
            isFavorite = { false } // Sports are never favorites
        )

        val spanCount = resources.getInteger(com.livetvpro.R.integer.channel_grid_span_count)
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
            binding.swipeRefresh.isRefreshing = isLoading
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
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSports()
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
