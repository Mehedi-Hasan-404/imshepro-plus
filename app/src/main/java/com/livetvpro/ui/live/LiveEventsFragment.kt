package com.livetvpro.ui.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
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
        
        // Load data
        viewModel.loadEventCategories()
        viewModel.filterEvents(EventStatus.LIVE, "evt_cat_all")
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
            
            PlayerActivity.startWithEvent(requireContext(), event)
        }
        
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
        }
    }

    private fun setupStatusFilters() {
        val clickListener = View.OnClickListener { view ->
            // Prevent re-clicking the same chip
            if ((view as Chip).isChecked) {
                val status = when (view.id) {
                    R.id.chip_all -> null
                    R.id.chip_live -> EventStatus.LIVE
                    R.id.chip_upcoming -> EventStatus.UPCOMING
                    R.id.chip_recent -> EventStatus.RECENT
                    else -> null
                }
                
                // Only update if different from current
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
        
        // Set initial selection
        binding.chipLive.isChecked = true
        selectedStatusFilter = EventStatus.LIVE
    }

    private fun updateChipSelection(selectedChip: Chip) {
        listOf(binding.chipAll, binding.chipLive, binding.chipUpcoming, binding.chipRecent).forEach {
            it.isChecked = (it == selectedChip)
        }
    }

    private fun observeViewModel() {
        viewModel.eventCategories.observe(viewLifecycleOwner) { categories ->
            categoryAdapter.submitList(categories)
            binding.categoryRecycler.visibility = if (categories.isNotEmpty()) View.VISIBLE else View.GONE
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

    override fun onDestroyView() {
        super.onDestroyView()
        eventAdapter.stopCountdown()
        _binding = null
    }
}
