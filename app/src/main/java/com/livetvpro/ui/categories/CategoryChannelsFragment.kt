package com.livetvpro.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    /**
     * Implementation of SearchableFragment interface.
     * Triggered when the user types in the main activity's search bar.
     */
    override fun onSearchQuery(query: String) {
        viewModel.searchChannels(query)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set Toolbar Title from Category Name
        try {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            if (viewModel.categoryName.isNotEmpty()) {
                toolbarTitle?.text = viewModel.categoryName
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupRecyclerView()
        observeViewModel()
        
        // Initial data load if using safe args or category ID
        // Note: categoryId is usually passed via SafeArgs. 
        // Ensure your nav_graph provides this.
        arguments?.getString("categoryId")?.let {
            viewModel.loadChannels(it)
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                // Starts the player activity with the selected channel
                ChannelPlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { channel ->
                // Toggles favorite status in the local database
                viewModel.toggleFavorite(channel)
                // Small delay to allow the DB to update before refreshing the UI star icon
                binding.root.postDelayed({
                    channelAdapter.refreshItem(channel.id)
                }, 100)
            },
            isFavorite = { channelId ->
                // Checks the current favorite state for the star icon color
                viewModel.isFavorite(channelId)
            }
        )

        binding.recyclerViewChannels.apply {
            // Span count 3 ensures 3 channel cards per row
            layoutManager = GridLayoutManager(context, 3)
            adapter = channelAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        // Observe the filtered list (handles both category load and search results)
        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)
            binding.emptyView.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        }

        // Handle the loading spinner visibility
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // Handle error states (e.g., no internet or API failure)
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
            } else {
                binding.errorView.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the star icons when coming back from the player
        binding.root.postDelayed({
            channelAdapter.refreshAll()
        }, 50)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

