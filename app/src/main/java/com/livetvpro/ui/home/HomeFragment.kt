// File: app/src/main/java/com/livetvpro/ui/home/HomeFragment.kt
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
import com.livetvpro.utils.ListenerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment(), SearchableFragment {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    
    @Inject
    lateinit var listenerManager: ListenerManager
    private var hasTriggeredListener = false

    override fun onSearchQuery(query: String) {
        viewModel.searchCategories(query)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        setupSwipeRefresh()
    }

    private fun setupRecyclerView() {
        categoryAdapter = CategoryAdapter { category ->
            // Try to show Ad
            if (!hasTriggeredListener) {
                hasTriggeredListener = listenerManager.onPageInteraction(ListenerConfig.PAGE_HOME)
            }
            
            // Navigate
            val bundle = bundleOf(
                "categoryId" to category.id,
                "categoryName" to category.name
            )
            findNavController().navigate(R.id.action_home_to_category, bundle)
        }

        binding.recyclerViewCategories.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = categoryAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredCategories.observe(viewLifecycleOwner) { categories ->
            categoryAdapter.submitList(categories)
            binding.emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewCategories.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
        }
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = isLoading
        }
        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
                binding.recyclerViewCategories.visibility = View.GONE
            } else {
                binding.errorView.visibility = View.GONE
                binding.recyclerViewCategories.visibility = View.VISIBLE
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { viewModel.loadCategories() }
        binding.retryButton.setOnClickListener { viewModel.retry() }
    }
    
    override fun onResume() {
        super.onResume()
        hasTriggeredListener = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

