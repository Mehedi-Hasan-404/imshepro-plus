package com.livetvpro.ui.categories

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.CategoryGroup
import com.livetvpro.ui.adapters.CategoryGroupChipAdapter
import com.livetvpro.ui.adapters.CategoryGroupDialogAdapter
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryGroupAdapter: CategoryGroupChipAdapter

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

        try {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            if (viewModel.categoryName.isNotEmpty()) {
                toolbarTitle?.text = viewModel.categoryName
            }
        } catch (e: Exception) {
            Log.e("CategoryChannels", "Error setting toolbar title", e)
        }

        setupCategoryGroupsRecyclerView()
        setupRecyclerView()
        observeViewModel()

        // Set up category icon click listener
        binding.categoryIcon.setOnClickListener {
            showCategoryGroupsDialog()
        }

        // Only load if we haven't already (prevents reloading on configuration changes)
        if (viewModel.filteredChannels.value.isNullOrEmpty()) {
            currentCategoryId?.let {
                viewModel.loadChannels(it)
            }
        }
    }

    private fun setupCategoryGroupsRecyclerView() {
        categoryGroupAdapter = CategoryGroupChipAdapter { groupName ->
            viewModel.selectGroup(groupName)
        }

        binding.recyclerViewCategoryGroups.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryGroupAdapter
        }
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val shouldBlock = listenerManager.onPageInteraction(
                    ListenerConfig.PAGE_CHANNELS,
                    uniqueId = currentCategoryId
                )

                if (shouldBlock) {
                    return@ChannelAdapter
                }

                // Check for multiple links
                if (channel.links != null && channel.links.isNotEmpty() && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)
                } else {
                    PlayerActivity.startWithChannel(requireContext(), channel)
                }
            },
            onFavoriteToggle = { channel ->
                // 1. Toggle in ViewModel
                viewModel.toggleFavorite(channel)

                // 2. Refresh ONLY the specific item to update the heart icon.
                // We reduce the delay to 50ms (just enough for the DB write to likely finish)
                // Note: For instant updates, your ViewModel should optimistically update its cache.
                lifecycleScope.launch {
                    delay(50) 
                    if (_binding != null) {
                        channelAdapter.refreshItem(channel.id)
                    }
                }
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        // Get responsive column count from resources
        val columnCount = resources.getInteger(R.integer.grid_column_count)

        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, columnCount)
            adapter = channelAdapter
            // Optimization: Prevents RecyclerView from blinking on updates
            itemAnimator = null 
        }
    }

    private fun showLinkSelectionDialog(channel: Channel) {
        val links = channel.links ?: return
        val linkLabels = links.map { it.quality }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream")
            .setItems(linkLabels) { dialog, which ->
                val selectedLink = links[which]

                val modifiedChannel = channel.copy(
                    streamUrl = selectedLink.url
                )

                PlayerActivity.startWithChannel(requireContext(), modifiedChannel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCategoryGroupsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_category_groups, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recycler_view_categories)
        val searchEditText = dialogView.findViewById<EditText>(R.id.search_category)
        val closeButton = dialogView.findViewById<ImageView>(R.id.close_button)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        
        val allGroups = viewModel.categoryGroups.value ?: emptyList()
        var filteredGroups = allGroups.toList()
        
        val dialogAdapter = CategoryGroupDialogAdapter { groupName ->
            viewModel.selectGroup(groupName)
            dialog.dismiss()
        }
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dialogAdapter
        }
        
        dialogAdapter.submitList(filteredGroups)
        
        // Search functionality
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                filteredGroups = if (query.isEmpty()) {
                    allGroups
                } else {
                    allGroups.filter { it.contains(query, ignoreCase = true) }
                }
                dialogAdapter.submitList(filteredGroups)
            }
        })
        
        closeButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
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
        
        // Observe category groups and update chips
        viewModel.categoryGroups.observe(viewLifecycleOwner) { groups ->
            updateCategoryChips(groups)
        }
        
        // Observe current group selection
        viewModel.currentGroup.observe(viewLifecycleOwner) { selectedGroup ->
            updateCategoryChips(viewModel.categoryGroups.value ?: emptyList(), selectedGroup)
        }
    }
    
    private fun updateCategoryChips(groups: List<String>, selectedGroup: String = "All") {
        val categoryGroups = groups.map { groupName ->
            CategoryGroup(
                name = groupName,
                isSelected = groupName == selectedGroup
            )
        }
        categoryGroupAdapter.submitList(categoryGroups)
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
        // REMOVED: The full refreshAll() call that was causing the screen to flash
        // Adapters retain state, so we don't need to force a redraw of the whole list here.
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
