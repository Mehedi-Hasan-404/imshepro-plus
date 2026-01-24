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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!
    
    // Using the same ViewModel setup as Source 16
    [span_3](start_span)private val viewModel: CategoryChannelsViewModel by viewModels()[span_3](end_span)
    private lateinit var channelAdapter: ChannelAdapter
    
    @Inject
    lateinit var listenerManager: NativeListenerManager

    private var currentCategoryId: String? = null

    override fun onSearchQuery(query: String) {
        viewModel.searchChannels(query)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        currentCategoryId = arguments?.getString("categoryId")

        // Setup Toolbar Title safely
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

        currentCategoryId?.let {
            viewModel.loadChannels(it)
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                // 1. Handle Native Listener / Blocking Logic
                val shouldBlock = listenerManager.onPageInteraction(
                    ListenerConfig.PAGE_CHANNELS, 
                    uniqueId = currentCategoryId
                [span_4](start_span))

                if (shouldBlock) {
                    return@ChannelAdapter
                }
                
                // 2. Production Check: Does this channel have multiple quality links?
                if (channel.links != null && channel.links.isNotEmpty() && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)[span_4](end_span)
                } else {
                    // Single link or no links - go directly to player
                    PlayerActivity.startWithChannel(requireContext(), channel)
                }
            },
            onFavoriteToggle = { channel ->
                // Toggle in ViewModel
                [span_5](start_span)viewModel.toggleFavorite(channel)[span_5](end_span)
                
                // Refresh specific item after small delay to allow DB update
                // Removing setHasFixedSize(true) makes this more reliable
                binding.root.postDelayed({ 
                    if (_binding != null) { // Safety check to prevent crash if fragment destroyed
                        channelAdapter.refreshItem(channel.id) 
                    }
                }, 100)
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        binding.recyclerViewChannels.apply {
            [span_6](start_span)layoutManager = GridLayoutManager(context, 3)[span_6](end_span)
            adapter = channelAdapter
            // IMPORTANT: Keep setHasFixedSize(true) REMOVED to ensure icon redraws correctly
            // setHasFixedSize(true) <- Do not uncomment
        }
    }

    // Logic to handle channels with multiple stream qualities (HD, SD, etc.)
    private fun showLinkSelectionDialog(channel: Channel) {
        val links = channel.links ?: return
        val linkLabels = links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            [span_7](start_span).setTitle("Select Stream Quality")[span_7](end_span)
            .setItems(linkLabels) { dialog, which ->
                val selectedLink = links[which]
                
                // Create copy with selected URL but keep links list intact
                val modifiedChannel = channel.copy(
                    streamUrl = selectedLink.url
                [span_8](start_span))
                
                // Pass index 'which' so player knows which quality chip to select
                PlayerActivity.startWithChannel(requireContext(), modifiedChannel, which)[span_8](end_span)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        // Refresh all items when returning (e.g. from Player) to ensure favorites are up to date
        binding.root.postDelayed({ channelAdapter.refreshAll() }, 50)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
