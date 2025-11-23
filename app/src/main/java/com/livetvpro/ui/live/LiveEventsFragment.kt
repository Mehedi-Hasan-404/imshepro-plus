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
import com.livetvpro.data.models.EventStatus
import com.livetvpro.databinding.FragmentLiveEventsBinding
import com.livetvpro.ui.adapters.LiveEventAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LiveEventsFragment : Fragment() {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFilters()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        eventAdapter = LiveEventAdapter { event ->
            EventPlayerActivity.start(requireContext(), event.id)
        }

        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { 
            viewModel.filterEvents(null)
            updateChipSelection(binding.chipAll)
        }
        binding.chipLive.setOnClickListener { 
            viewModel.filterEvents(EventStatus.LIVE)
            updateChipSelection(binding.chipLive)
        }
        binding.chipUpcoming.setOnClickListener { 
            viewModel.filterEvents(EventStatus.UPCOMING)
            updateChipSelection(binding.chipUpcoming)
        }
        binding.chipRecent.setOnClickListener { 
            viewModel.filterEvents(EventStatus.RECENT)
            updateChipSelection(binding.chipRecent)
        }

        // Default to Live
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
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
