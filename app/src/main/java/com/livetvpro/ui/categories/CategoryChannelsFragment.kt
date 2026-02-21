package com.livetvpro.ui.categories

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
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
import com.livetvpro.utils.DeviceUtils
import com.livetvpro.utils.NativeListenerManager
import com.livetvpro.utils.RetryHandler
import com.livetvpro.utils.Refreshable
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.utils.FloatingPlayerHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment(), SearchableFragment, Refreshable {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    @Inject
    lateinit var listenerManager: NativeListenerManager

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private var currentCategoryId: String? = null

    // ── Number-pad live search (TV devices) ──────────────────────────────────
    // When a user presses digit keys on the remote the fragment accumulates them
    // into a buffer and live-filters the channel list. After a short idle period
    // the buffer resets.  On non-TV devices this is ignored (users have keyboard).
    private var numpadBuffer = ""
    private val numpadHandler = Handler(Looper.getMainLooper())
    private val numpadResetRunnable = Runnable {
        numpadBuffer = ""
        // Clear the numpad filter — restore the current search query instead
        viewModel.searchChannels("")
    }
    private val NUMPAD_RESET_MS = 2000L

    // ────────────────────────────────────────────────────────────────────────

    override fun onSearchQuery(query: String) {
        viewModel.searchChannels(query)
    }

    override fun refreshData() {
        currentCategoryId?.let { viewModel.loadChannels(it) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val columnCount = resources.getInteger(R.integer.grid_column_count)
        (binding.recyclerViewChannels.layoutManager as? GridLayoutManager)?.spanCount = columnCount
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

        currentCategoryId = arguments?.getString("categoryId")

        try {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            if (viewModel.categoryName.isNotEmpty()) {
                toolbarTitle?.text = viewModel.categoryName
            }
        } catch (_: Exception) {}

        setupTabLayout()
        setupRecyclerView()
        setupRetryHandling()
        observeViewModel()

        binding.groupsIcon.setOnClickListener { showGroupsDialog() }

        if (viewModel.filteredChannels.value.isNullOrEmpty()) {
            currentCategoryId?.let { viewModel.loadChannels(it) }
        }

        // On TV: intercept digit keys at the fragment root for live search
        if (DeviceUtils.isTvDevice) {
            setupTvNumpadSearch()
        }
    }

    // ── TV number-pad search ──────────────────────────────────────────────────
    // The fragment root intercepts 0-9 key events.  Each digit appends to a
    // buffer which is fed into the ViewModel's search filter.  After the user
    // stops typing for NUMPAD_RESET_MS the buffer clears and results reset.
    private fun setupTvNumpadSearch() {
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()

        binding.root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val digit: String? = when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> "0"
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> "1"
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> "2"
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> "3"
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> "4"
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> "5"
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> "6"
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> "7"
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> "8"
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> "9"
                else -> null
            }

            if (digit != null) {
                numpadBuffer += digit
                // Reset idle timer
                numpadHandler.removeCallbacks(numpadResetRunnable)
                numpadHandler.postDelayed(numpadResetRunnable, NUMPAD_RESET_MS)
                // Live-filter by channel name containing the typed digits
                viewModel.searchChannels(numpadBuffer)
                // Move focus into the list so D-pad can navigate results
                binding.recyclerViewChannels.requestFocus()
                return@setOnKeyListener true
            }

            // DEL / BACK clears the numpad buffer
            if (keyCode == KeyEvent.KEYCODE_DEL && numpadBuffer.isNotEmpty()) {
                numpadBuffer = numpadBuffer.dropLast(1)
                numpadHandler.removeCallbacks(numpadResetRunnable)
                if (numpadBuffer.isEmpty()) {
                    viewModel.searchChannels("")
                } else {
                    viewModel.searchChannels(numpadBuffer)
                    numpadHandler.postDelayed(numpadResetRunnable, NUMPAD_RESET_MS)
                }
                return@setOnKeyListener true
            }

            false
        }
    }

    // ── RecyclerView setup ────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val shouldBlock = listenerManager.onPageInteraction(
                    ListenerConfig.PAGE_CHANNELS,
                    uniqueId = currentCategoryId
                )
                if (shouldBlock) return@ChannelAdapter

                if (channel.links != null && channel.links.isNotEmpty() && channel.links.size > 1) {
                    showLinkSelectionDialog(channel)
                } else {
                    launchPlayer(channel, -1)
                }
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                lifecycleScope.launch {
                    delay(50)
                    if (_binding != null) channelAdapter.refreshItem(channel.id)
                }
            },
            isFavorite = { channelId -> viewModel.isFavorite(channelId) }
        )

        val columnCount = resources.getInteger(R.integer.grid_column_count)

        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, columnCount)
            adapter = channelAdapter
            itemAnimator = null

            // ── TV D-pad focus ──────────────────────────────────────────────
            // FOCUS_AFTER_DESCENDANTS: D-pad can move focus into child items.
            // Without this the RecyclerView itself swallows focus and child
            // items are never highlighted.
            if (DeviceUtils.isTvDevice) {
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                isFocusable            = true
                isFocusableInTouchMode = false
            }
        }
    }

    // ── Retry handling ────────────────────────────────────────────────────────

    private fun setupRetryHandling() {
        RetryHandler.setupGlobal(
            lifecycleOwner = viewLifecycleOwner,
            viewModel      = viewModel,
            activity       = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            contentView    = binding.recyclerViewChannels,
            swipeRefresh   = binding.swipeRefresh,
            progressBar    = binding.progressBar,
            emptyView      = binding.emptyView
        )
    }

    // ── Player launch ─────────────────────────────────────────────────────────

    private fun launchPlayer(channel: Channel, linkIndex: Int) {
        val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
        val hasPermission   = FloatingPlayerHelper.hasOverlayPermission(requireContext())

        if (floatingEnabled) {
            if (!hasPermission) {
                PlayerActivity.startWithChannel(
                    requireContext(), channel, linkIndex,
                    categoryId    = currentCategoryId,
                    selectedGroup = viewModel.currentGroup.value
                )
                return
            }
            try {
                FloatingPlayerHelper.launchFloatingPlayer(requireContext(), channel, linkIndex)
            } catch (_: Exception) {
                PlayerActivity.startWithChannel(
                    requireContext(), channel, linkIndex,
                    categoryId    = currentCategoryId,
                    selectedGroup = viewModel.currentGroup.value
                )
            }
        } else {
            PlayerActivity.startWithChannel(
                requireContext(), channel, linkIndex,
                categoryId    = currentCategoryId,
                selectedGroup = viewModel.currentGroup.value
            )
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showLinkSelectionDialog(channel: Channel) {
        val links      = channel.links ?: return
        val linkLabels = links.map { it.quality }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Multiple Links Available")
            .setItems(linkLabels) { dialog, which ->
                val selectedLink    = links[which]
                val modifiedChannel = channel.copy(streamUrl = selectedLink.url)
                launchPlayer(modifiedChannel, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupsDialog() {
        val dialogView    = layoutInflater.inflate(R.layout.dialog_category_groups, null)
        val recyclerView  = dialogView.findViewById<RecyclerView>(R.id.recycler_view_groups)
        val searchEditText = dialogView.findViewById<EditText>(R.id.search_group)
        val clearSearchBtn = dialogView.findViewById<ImageView>(R.id.clear_search_button)
        val closeButton   = dialogView.findViewById<ImageView>(R.id.close_button)

        searchEditText.typeface = resources.getFont(R.font.bergen_sans)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val allGroups       = viewModel.categoryGroups.value ?: emptyList()
        var filteredGroups  = allGroups.toList()

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
            adapter        = dialogAdapter
        }

        dialogAdapter.submitList(filteredGroups)

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            clearSearchBtn.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                filteredGroups = if (query.isEmpty()) allGroups
                else allGroups.filter { it.contains(query, ignoreCase = true) }
                dialogAdapter.submitList(filteredGroups)
            }
        })

        clearSearchBtn.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.clearFocus()
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // ── ViewModel observers ───────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)

            // On TV: after a data change auto-focus the first item so D-pad
            // navigation works immediately without an extra click.
            if (DeviceUtils.isTvDevice && channels.isNotEmpty()) {
                binding.recyclerViewChannels.post {
                    binding.recyclerViewChannels
                        .findViewHolderForAdapterPosition(0)
                        ?.itemView
                        ?.requestFocus()
                }
            }
        }

        viewModel.categoryGroups.observe(viewLifecycleOwner) { groups ->
            val hasGroups = groups.isNotEmpty()
            binding.groupsHeader.visibility  = if (hasGroups) View.VISIBLE else View.GONE
            binding.headerDivider.visibility = if (hasGroups) View.VISIBLE else View.GONE

            if (hasGroups) updateTabs(groups)
            else binding.tabLayoutGroups.removeAllTabs()
        }

        viewModel.currentGroup.observe(viewLifecycleOwner) { selectedGroup ->
            val groups   = viewModel.categoryGroups.value ?: emptyList()
            val tabIndex = groups.indexOf(selectedGroup)
            if (tabIndex >= 0 && tabIndex < binding.tabLayoutGroups.tabCount) {
                binding.tabLayoutGroups.getTabAt(tabIndex)?.select()
            }
        }
    }

    // ── Tab layout ────────────────────────────────────────────────────────────

    private fun setupTabLayout() {
        binding.tabLayoutGroups.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.text?.toString()?.let { groupName -> viewModel.selectGroup(groupName) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateTabs(groups: List<String>) {
        binding.tabLayoutGroups.removeAllTabs()
        groups.forEach { groupName ->
            binding.tabLayoutGroups.addTab(binding.tabLayoutGroups.newTab().setText(groupName))
        }
        if (binding.tabLayoutGroups.tabCount > 0) binding.tabLayoutGroups.getTabAt(0)?.select()

        binding.tabLayoutGroups.post {
            for (i in 0 until binding.tabLayoutGroups.tabCount) {
                val tab         = binding.tabLayoutGroups.getTabAt(i)
                val tabView     = tab?.view
                val tabTextView = tabView?.findViewById<TextView>(android.R.id.text1)
                tabTextView?.typeface = resources.getFont(R.font.bergen_sans)
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        numpadHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}
