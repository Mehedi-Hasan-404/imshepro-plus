package com.livetvpro.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.MainActivity
import com.livetvpro.R
import com.livetvpro.databinding.FragmentHomeBinding
import com.livetvpro.ui.adapters.CategoryAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter
    
    private var searchView: SearchView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        observeViewModel()
        setupSwipeRefresh()
    }

    private fun setupToolbar() {
        // Setup search button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_search)?.setOnClickListener {
            showSearchDialog()
        }

        // Setup favorites button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_favorites)?.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_favorites)
        }

        // Setup theme toggle button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_theme_toggle)?.setOnClickListener {
            (activity as? MainActivity)?.toggleTheme()
        }
    }

    private fun showSearchDialog() {
        val searchView = SearchView(requireContext())
        searchView.queryHint = "Search categories..."
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Search Categories")
            .setView(searchView)
            .setNegativeButton("Close", null)
            .create()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.searchCategories(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchCategories(newText ?: "")
                return true
            }
        })

        dialog.show()
    }

    private fun setupRecyclerView() {
        categoryAdapter = CategoryAdapter { category ->
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
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.swipeRefresh.isRefreshing = isLoading
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

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadCategories()
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
