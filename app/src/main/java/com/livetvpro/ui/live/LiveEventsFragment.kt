package com.livetvpro.ui.live

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.EventCategory
import com.livetvpro.data.models.EventStatus
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.FragmentLiveEventsBinding
import com.livetvpro.ui.adapters.EventCategoryAdapter
import com.livetvpro.ui.adapters.LiveEventAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LiveEventsFragment : Fragment() {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter
    private lateinit var categoryAdapter: EventCategoryAdapter
    
    @Inject
    lateinit var listenerManager: NativeListenerManager

    private var selectedCategoryId: String = "evt_cat_all"
    private var selectedStatusFilter: EventStatus? = null

    // Handler for dynamic updates
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            // Refresh the event list every second
            viewModel.filterEvents(selectedStatusFilter, selectedCategoryId)
            updateHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupCategoryRecycler()
        setupEventRecycler()
        setupStatusFilters()
        observeViewModel()
        
        // Load data - Start with "All" filter
        viewModel.loadEventCategories()
        viewModel.filterEvents(null, "evt_cat_all")
        
        // Start dynamic updates
        startDynamicUpdates()
    }

    private fun startDynamicUpdates() {
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.post(updateRunnable)
    }

    private fun stopDynamicUpdates() {
        updateHandler.removeCallbacks(updateRunnable)
    }

    private fun setupCategoryRecycler() {
        categoryAdapter = EventCategoryAdapter { category ->
            selectedCategoryId = category.id
            viewModel.filterEvents(selectedStatusFilter, selectedCategoryId)
        }
        
        binding.categoryRecycler.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
        }
    }

    private fun setupEventRecycler() {
        eventAdapter = LiveEventAdapter(requireContext(), emptyList()) { event ->
            val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_LIVE_EVENTS)
            
            if (shouldBlock) return@LiveEventAdapter
            
            // Show link selection dialog if multiple links exist
            if (event.links.size > 1) {
                showLinkSelectionDialog(event)
            } else {
                // Single link or no links - go directly to player
                PlayerActivity.startWithEvent(requireContext(), event)
            }
        }
        
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
        }
    }

    private fun showLinkSelectionDialog(event: LiveEvent) {
        val linkLabels = event.links.map { it.label }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream Quality")
            .setItems(linkLabels) { dialog, which ->
                // ✅ FIX: Pass the full event object containing all links.
                // Pass the selected index 'which' so player knows what to start with.
                PlayerActivity.startWithEvent(requireContext(), event, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupStatusFilters() {
        val clickListener = View.OnClickListener { view ->
            if ((view as Chip).isChecked) {
                val status = when (view.id) {
                    R.id.chip_all -> null
                    R.id.chip_live -> EventStatus.LIVE
                    R.id.chip_upcoming -> EventStatus.UPCOMING
                    R.id.chip_recent -> EventStatus.RECENT
                    else -> null
                }
                
                if (status != selectedStatusFilter) {
                    selectedStatusFilter = status
                    viewModel.filterEvents(selectedStatusFilter, selectedCategoryId)
                    updateChipSelection(view)
                }
            }
        }
        
        binding.chipAll.setOnClickListener(clickListener)
        binding.chipLive.setOnClickListener(clickListener)
        binding.chipUpcoming.setOnClickListener(clickListener)
        binding.chipRecent.setOnClickListener(clickListener)
        
        // Set initial selection to "All"
        binding.chipAll.isChecked = true
        selectedStatusFilter = null
    }

    private fun updateChipSelection(selectedChip: Chip) {
        listOf(binding.chipAll, binding.chipLive, binding.chipUpcoming, binding.chipRecent).forEach {
            it.isChecked = (it == selectedChip)
        }
    }

    private fun observeViewModel() {
        viewModel.eventCategories.observe(viewLifecycleOwner) { categories ->
            binding.categoryRecycler.visibility = View.VISIBLE
            categoryAdapter.submitList(categories)
        }
        
        viewModel.filteredEvents.observe(viewLifecycleOwner) { events ->
            eventAdapter.updateData(events)
            binding.emptyView.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.errorView.visibility = if (error != null) View.VISIBLE else View.GONE
            if (error != null) binding.errorText.text = error
        }
    }

    override fun onResume() {
        super.onResume()
        startDynamicUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopDynamicUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopDynamicUpdates()
        eventAdapter.stopCountdown()
        _binding = null
    }
}
