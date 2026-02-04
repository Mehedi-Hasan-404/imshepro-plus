package com.livetvpro.ui.sports

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.data.models.Sport
import com.livetvpro.databinding.FragmentSportsBinding
import com.livetvpro.ui.adapters.SportAdapter
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.NativeListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SportsFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentSportsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SportsViewModel by viewModels()
    private lateinit var sportAdapter: SportAdapter

    @Inject
    lateinit var listenerManager: NativeListenerManager

    override fun onSearchQuery(query: String) {
        viewModel.searchSports(query)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        setupSwipeRefresh()
    }

    private fun setupRecyclerView() {
        sportAdapter = SportAdapter { sport ->
            val shouldBlock = listenerManager.onPageInteraction(ListenerConfig.PAGE_LIVE_EVENTS)
            
            if (shouldBlock) return@SportAdapter
            
            // Show link selection dialog if multiple links exist
            if (sport.links.size > 1) {
                showLinkSelectionDialog(sport)
            } else {
                // Single link or no links - go directly to player
                PlayerActivity.startWithSport(requireContext(), sport)
            }
        }

        binding.recyclerViewSports.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sportAdapter
            setHasFixedSize(true)
        }
    }

    private fun showLinkSelectionDialog(sport: Sport) {
        val linkLabels = sport.links.map { it.quality }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Stream Quality")
            .setItems(linkLabels) { dialog, which ->
                PlayerActivity.startWithSport(requireContext(), sport, which)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.filteredSports.observe(viewLifecycleOwner) { sports ->
            sportAdapter.submitList(sports)
            binding.emptyView.visibility = if (sports.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewSports.visibility = if (sports.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
                binding.recyclerViewSports.visibility = View.GONE
            } else {
                binding.errorView.visibility = View.GONE
                binding.recyclerViewSports.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadSports()
        }
        
        binding.retryButton.setOnClickListener {
            viewModel.retry()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
