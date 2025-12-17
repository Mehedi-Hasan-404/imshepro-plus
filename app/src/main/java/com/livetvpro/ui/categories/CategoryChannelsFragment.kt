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

        // Set Toolbar Title
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

        arguments?.getString("categoryId")?.let {
            viewModel.loadChannels(it)
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                ChannelPlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                binding.root.postDelayed({
                    channelAdapter.refreshItem(channel.id)
                }, 100)
            },
            isFavorite = { channelId ->
                viewModel.isFavorite(channelId)
            }
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
            val isEmpty = viewModel.filteredChannels.value?.isEmpty() ?: true
            updateUiState(isEmpty, isLoading)
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
            } else {
                binding.errorView.visibility = View.GONE
            }
        }
    }

    /**
     * Fixes the overlapping issue. 
     * Only shows Empty View if not loading and list is empty.
     */
    private fun updateUiState(isListEmpty: Boolean, isLoading: Boolean) {
        if (isLoading) {
            binding.emptyView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = if (isListEmpty) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.postDelayed({
            channelAdapter.refreshAll()
        }, 50)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

