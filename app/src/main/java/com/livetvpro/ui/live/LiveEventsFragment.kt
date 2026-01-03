// File: app/src/main/java/com/livetvpro/ui/live/LiveEventsFragment.kt
package com.livetvpro.ui.live

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.livetvpro.R
import com.livetvpro.data.models.EventStatus
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentLiveEventsBinding
import com.livetvpro.ui.adapters.LiveEventAdapter
import com.livetvpro.utils.ListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LiveEventsFragment : Fragment() {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter
    
    @Inject
    lateinit var listenerManager: ListenerManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupFilters()
        observeViewModel()
        viewModel.filterEvents(EventStatus.LIVE)
    }

    private fun setupRecyclerView() {
        eventAdapter = LiveEventAdapter { event ->
            try {
                // CHANGED: No uniqueId passed.
                // This implies: "Show ad once for the entire Live Events section"
                val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_LIVE_EVENTS)
                
                if (shouldBlock) {
                    return@LiveEventAdapter
                }
                
                // Open Player
                EventPlayerActivity.start(requireContext(), event.id)
                
            } catch (e: Exception) {
                Log.e("LiveEvents", "Error starting event player", e)
            }
        }
        
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
            setHasFixedSize(false)
        }
    }

    // ... (Rest of file remains unchanged: setupFilters, updateChipSelection, observeViewModel, etc.)

    private fun setupFilters() {
        val clickListener = View.OnClickListener { view ->
            val status = when (view.id) {
                R.id.chip_live -> EventStatus.LIVE
                R.id.chip_upcoming -> EventStatus.UPCOMING
                R.id.chip_recent -> EventStatus.RECENT
                else -> null
            }
            viewModel.filterEvents(status)
            updateChipSelection(view as Chip)
        }
        
        binding.chipAll.setOnClickListener(clickListener)
        binding.chipLive.setOnClickListener(clickListener)
        binding.chipUpcoming.setOnClickListener(clickListener)
        binding.chipRecent.setOnClickListener(clickListener)
        updateChipSelection(binding.chipLive)
    }

    private fun updateChipSelection(selectedChip: Chip) {
        listOf(binding.chipAll, binding.chipLive, binding.chipUpcoming, binding.chipRecent).forEach {
            it.isChecked = (it == selectedChip)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredEvents.observe(viewLifecycleOwner) { events ->
            eventAdapter.submitList(events)
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
        _binding = null
    }
}

