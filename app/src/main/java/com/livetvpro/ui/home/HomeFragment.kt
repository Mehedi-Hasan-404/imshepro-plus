package com.livetvpro.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.SearchableFragment
import com.livetvpro.data.models.ListenerConfig
import com.livetvpro.databinding.FragmentHomeBinding
import com.livetvpro.ui.adapters.CategoryAdapter
import com.livetvpro.utils.NativeListenerManager
import com.livetvpro.utils.RetryHandler
import com.livetvpro.utils.Refreshable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment(), SearchableFragment, Refreshable {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter

    @Inject
    lateinit var listenerManager: NativeListenerManager

    override fun onSearchQuery(query: String) {
        viewModel.searchCategories(query)
    }
    
    // Implement Refreshable interface for toolbar refresh icon
    override fun refreshData() {
        viewModel.refresh()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupRetryHandling()
    }
    
    override fun onResume() {
        super.onResume()
        // Let RetryViewModel handle resume logic
        viewModel.onResume()
    }

    private fun setupRecyclerView() {
        categoryAdapter = CategoryAdapter { category ->
            val shouldBlock = listenerManager.onPageInteraction(
                pageType = ListenerConfig.PAGE_HOME,
                uniqueId = category.id
            )
            
            if (shouldBlock) {
                return@CategoryAdapter
            }
            
            val bundle = bundleOf(
                "categoryId" to category.id,
                "categoryName" to category.name
            )
            findNavController().navigate(R.id.action_home_to_category, bundle)
        }

        val columnCount = resources.getInteger(R.integer.grid_column_count)

        binding.recyclerViewCategories.apply {
            layoutManager = GridLayoutManager(context, columnCount)
            adapter = categoryAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupRetryHandling() {
        // Setup retry handler WITH pull-to-refresh support
        RetryHandler.setupWithRefresh(
            lifecycleOwner = viewLifecycleOwner,
            viewModel = viewModel,
            swipeRefresh = binding.swipeRefresh,  // ← Pull-to-refresh enabled!
            errorView = binding.errorView,
            errorText = binding.errorText,
            retryButton = binding.retryButton,
            contentView = binding.recyclerViewCategories,
            progressBar = binding.progressBar,
            emptyView = binding.emptyView
        )
        
        // Observe categories to update adapter
        viewModel.filteredCategories.observe(viewLifecycleOwner) { categories ->
            categoryAdapter.submitList(categories)
            binding.emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
