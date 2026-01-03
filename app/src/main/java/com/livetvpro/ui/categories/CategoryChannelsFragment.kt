package com.livetvpro.ui.categories

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity
import com.livetvpro.utils.ListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CategoryChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter
    
    @Inject
    lateinit var listenerManager: ListenerManager
    
    // Flag to track ad session. 
    // Initialized to false only when the view is created (Session Start).
    private var hasTriggeredListenerInThisCategory = false

    override fun onSearchQuery(query: String) {
        viewModel.searchChannels(query)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // SESSION START: Reset flag when entering the screen
        hasTriggeredListenerInThisCategory = false
        Log.d("CategoryChannels", "Session started: Ad trigger reset to false")
        
        try {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            if (viewModel.categoryName.isNotEmpty()) {
                toolbarTitle?.text = viewModel.categoryName
            }
        } catch (e: Exception) { 
            Log.e("CategoryChannels", "Error setting toolbar title", e)
        }

        setupRecyclerView()
        observeViewModel()

        arguments?.getString("categoryId")?.let {
            viewModel.loadChannels(it)
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                // 1. CHECK AD SESSION
                if (!hasTriggeredListenerInThisCategory) {
                    val listenerTriggered = listenerManager.onPageInteraction(ListenerConfig.PAGE_CHANNELS)
                    
                    if (listenerTriggered) {
                        // Browser opened. Mark session as "Ad Shown" and STOP.
                        // Do not open the player this time.
                        hasTriggeredListenerInThisCategory = true
                        Log.d("CategoryChannels", "Ad triggered. Blocking player launch.")
                        return@ChannelAdapter
                    }
                }
                
                // 2. OPEN PLAYER (Only if Ad didn't trigger)
                Log.d("CategoryChannels", "Opening channel player: ${channel.name}")
                ChannelPlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                // Small delay to allow animation/database update before UI refresh
                binding.root.postDelayed({ channelAdapter.refreshItem(channel.id) }, 100)
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = channelAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)
            updateUiState(channels.isEmpty(), viewModel.isLoading.value ?: false)
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            updateUiState(viewModel.filteredChannels.value?.isEmpty() ?: true, isLoading)
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.errorView.visibility = if (error != null) View.VISIBLE else View.GONE
            if (error != null) binding.errorText.text = error
        }
    }

    private fun updateUiState(isListEmpty: Boolean, isLoading: Boolean) {
        if (isLoading) {
            binding.emptyView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = if (isListEmpty) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // IMPORTANT: Do NOT reset hasTriggeredListenerInThisCategory here.
        // If we reset it, the user will see the ad again immediately after returning from the browser.
        
        binding.root.postDelayed({ channelAdapter.refreshAll() }, 50)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

