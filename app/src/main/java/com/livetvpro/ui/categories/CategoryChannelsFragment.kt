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
import com.google.android.material.tabs.TabLayout
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
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

        setupTabLayout()
        setupRecyclerView()
        observeViewModel()

        binding.groupsIcon.setOnClickListener {
            showGroupsDialog()
        }

        if (viewModel.filteredChannels.value.isNullOrEmpty()) {
            currentCategoryId?.let {
                viewModel.loadChannels(it)
            }
        }
    }

    private fun setupTabLayout() {
        binding.tabLayoutGroups.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let { groupName ->
                    viewModel.selectGroup(groupName)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
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

                if (channel.links != null && channel.links.isNotEmpty() && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)
                } else {
                    PlayerActivity.startWithChannel(requireContext(), channel)
                }
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                lifecycleScope.launch {
                    delay(50) 
                    if (_binding != null) {
                        channelAdapter.refreshItem(channel.id)
                    }
                }
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        val columnCount = resources.getInteger(R.integer.grid_column_count)

        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, columnCount)
            adapter = channelAdapter
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
                val modifiedChannel = channel.copy(streamUrl = selectedLink.url)
                PlayerActivity.startWithChannel(requireContext(), modifiedChannel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_category_groups, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recycler_view_groups)
        val searchEditText = dialogView.findViewById<EditText>(R.id.search_group)
        val clearSearchButton = dialogView.findViewById<ImageView>(R.id.clear_search_button)
        val closeButton = dialogView.findViewById<ImageView>(R.id.close_button)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        
        val allGroups = viewModel.categoryGroups.value ?: emptyList()
        var filteredGroups = allGroups.toList()
        
        val dialogAdapter = CategoryGroupDialogAdapter { groupName ->
            viewModel.selectGroup(groupName)
            val tabIndex = allGroups.indexOf(groupName)
            if (tabIndex >= 0 && tabIndex < binding.tabLayoutGroups.tabCount) {
                binding.tabLayoutGroups.getTabAt(tabIndex)?.select()
            }
            dialog.dismiss()
        }
        
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = dialogAdapter
        }
        
        dialogAdapter.submitList(filteredGroups)
        
        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            clearSearchButton.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }
        
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
        
        clearSearchButton.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.clearFocus()
        }
        
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
        
        viewModel.categoryGroups.observe(viewLifecycleOwner) { groups ->
            val hasGroups = groups.isNotEmpty()
            binding.groupsHeader.visibility = if (hasGroups) View.VISIBLE else View.GONE
            binding.headerDivider.visibility = if (hasGroups) View.VISIBLE else View.GONE
            
            if (hasGroups) {
                updateTabs(groups)
            } else {
                binding.tabLayoutGroups.removeAllTabs()
            }
        }
        
        viewModel.currentGroup.observe(viewLifecycleOwner) { selectedGroup ->
            val groups = viewModel.categoryGroups.value ?: emptyList()
            val tabIndex = groups.indexOf(selectedGroup)
            if (tabIndex >= 0 && tabIndex < binding.tabLayoutGroups.tabCount) {
                binding.tabLayoutGroups.getTabAt(tabIndex)?.select()
            }
        }
    }
    
    private fun updateTabs(groups: List<String>) {
        binding.tabLayoutGroups.removeAllTabs()
        groups.forEach { groupName ->
            val tab = binding.tabLayoutGroups.newTab().setText(groupName)
            binding.tabLayoutGroups.addTab(tab)
        }
        if (binding.tabLayoutGroups.tabCount > 0) {
            binding.tabLayoutGroups.getTabAt(0)?.select()
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
