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
import com.livetvpro.data.models.LiveEvent
import com.livetvpro.databinding.FragmentLiveEventsBinding
import com.livetvpro.ui.adapters.LiveEventAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.ui.player.dialogs.LinkSelectionDialog
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LiveEventsFragment : Fragment() {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter
    
    @Inject
    lateinit var listenerManager: NativeListenerManager

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
        eventAdapter = LiveEventAdapter(requireContext(), emptyList()) { event ->
            try {
                val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_LIVE_EVENTS)
                
                if (shouldBlock) {
                    return@LiveEventAdapter
                }
                
                // Check if event has multiple links
                if (event.links.size > 1) {
                    showLinkSelectionDialog(event)
                } else {
                    // Single link - play directly
                    PlayerActivity.startWithEvent(requireContext(), event)
                }
                
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
            val currentTime = System.currentTimeMillis()
            val apiDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            
            // Filter based on selected chip
            val filteredEvents = when {
                binding.chipAll.isChecked -> {
                    // Show ALL events sorted by: Live first, then Upcoming, then Recent
                    events.sortedWith(compareBy<LiveEvent> { event ->
                        try {
                            val startTime = apiDateFormat.parse(event.startTime)?.time ?: 0L
                            val endTime = if (!event.endTime.isNullOrEmpty()) {
                                apiDateFormat.parse(event.endTime)?.time ?: Long.MAX_VALUE
                            } else {
                                Long.MAX_VALUE
                            }
                            
                            when {
                                currentTime >= startTime && currentTime <= endTime -> 0 // Live first
                                currentTime < startTime -> 1 // Upcoming second
                                else -> 2 // Ended last
                            }
                        } catch (e: Exception) {
                            3 // Unknown status last
                        }
                    }.thenBy { it.startTime }) // Then sort by start time within each group
                }
                
                binding.chipLive.isChecked -> {
                    // Show only LIVE events (between start and end time)
                    events.filter { event ->
                        try {
                            val startTime = apiDateFormat.parse(event.startTime)?.time ?: 0L
                            val endTime = if (!event.endTime.isNullOrEmpty()) {
                                apiDateFormat.parse(event.endTime)?.time ?: Long.MAX_VALUE
                            } else {
                                Long.MAX_VALUE
                            }
                            currentTime >= startTime && currentTime <= endTime
                        } catch (e: Exception) {
                            false
                        }
                    }.sortedBy { it.startTime }
                }
                
                binding.chipUpcoming.isChecked -> {
                    // Show only UPCOMING events
                    events.filter { event ->
                        try {
                            val startTime = apiDateFormat.parse(event.startTime)?.time ?: 0L
                            currentTime < startTime
                        } catch (e: Exception) {
                            false
                        }
                    }.sortedBy { it.startTime }
                }
                
                binding.chipRecent.isChecked -> {
                    // Show only ENDED events
                    events.filter { event ->
                        try {
                            val startTime = apiDateFormat.parse(event.startTime)?.time ?: 0L
                            val endTime = if (!event.endTime.isNullOrEmpty()) {
                                apiDateFormat.parse(event.endTime)?.time ?: Long.MAX_VALUE
                            } else {
                                Long.MAX_VALUE
                            }
                            currentTime > endTime
                        } catch (e: Exception) {
                            false
                        }
                    }.sortedByDescending { it.startTime }
                }
                
                else -> events
            }
            
            eventAdapter.updateData(filteredEvents)
            binding.emptyView.visibility = if (filteredEvents.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewEvents.visibility = if (filteredEvents.isEmpty()) View.GONE else View.VISIBLE
        }
        
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
        
        viewModel.error.observe(viewLifecycleOwner) { error ->
            binding.errorView.visibility = if (error != null) View.VISIBLE else View.GONE
            if (error != null) binding.errorText.text = error
        }
    }

    private fun showLinkSelectionDialog(event: LiveEvent) {
        val dialog = LinkSelectionDialog(
            requireContext(),
            event.links,
            null
        ) { selectedLink ->
            val reorderedLinks = event.links.toMutableList()
            reorderedLinks.remove(selectedLink)
            reorderedLinks.add(0, selectedLink)

            val modifiedEvent = event.copy(
                links = reorderedLinks
            )
            PlayerActivity.startWithEvent(requireContext(), modifiedEvent)
        }
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // IMPORTANT: Stop countdown timer to prevent memory leaks
        eventAdapter.stopCountdown()
        _binding = null
    }
}
