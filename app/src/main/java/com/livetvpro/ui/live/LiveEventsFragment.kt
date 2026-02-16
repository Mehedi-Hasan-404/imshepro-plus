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
import com.livetvpro.utils.RetryHandler
import com.livetvpro.utils.Refreshable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LiveEventsFragment : Fragment(), Refreshable {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter
    private lateinit var categoryAdapter: EventCategoryAdapter
    
    @Inject
    lateinit var listenerManager: NativeListenerManager
    
    @Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager

    private var selectedCategoryId: String = "evt_cat_all"
    private var selectedStatusFilter: EventStatus? = null

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            viewModel.filterEvents(selectedStatusFilter, selectedCategoryId)
            updateHandler.postDelayed(this, 1000)
        }
    }

    override fun refreshData() {
        viewModel.refresh()
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
        setupRetryHandling()
        observeViewModel()
        
        viewModel.loadEventCategories()
        viewModel.filterEvents(null, "evt_cat_all")
        
        startDynamicUpdates()
    }

    private fun setupRetryHandling() {
        RetryHandler.setupGlobal(
            lifecycleOwner = viewLifecycleOwner,
            viewModel = viewModel,
            activity = requireActivity() as androidx.appcompat.app.AppCompatActivity,
            contentView = binding.recyclerViewEvents,
            swipeRefresh = binding.swipeRefresh,
            progressBar = binding.progressBar,
            emptyView = binding.emptyView
        )
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
        eventAdapter = LiveEventAdapter(requireContext(), emptyList(), preferencesManager)
        
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
        }
    }

    private fun showLinkSelectionDialog(event: LiveEvent) {
        val linkLabels = event.links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream")
            .setItems(linkLabels) { dialog, which ->
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
        }
    }

    override fun onResume() {
        super.onResume()
        startDynamicUpdates()
        viewModel.onResume()
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
