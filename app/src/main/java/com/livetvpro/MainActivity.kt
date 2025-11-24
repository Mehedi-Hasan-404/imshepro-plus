package com.livetvpro

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var preferencesManager: PreferencesManager
    
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved theme
        val isDarkTheme = preferencesManager.isDarkTheme()
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupNavigation()
        setupDrawer()
        setupSearch()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // We'll use custom title TextView
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Top-level destinations where hamburger menu should show
        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.contactFragment
        )

        // Setup drawer navigation
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        if (!navController.popBackStack(R.id.homeFragment, false)) {
                            navController.navigate(R.id.homeFragment)
                        }
                    }
                }
                R.id.liveEventsFragment -> {
                    if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                        navController.navigate(R.id.liveEventsFragment)
                    }
                }
                R.id.contactFragment -> {
                    if (navController.currentDestination?.id != R.id.contactFragment) {
                        navController.navigate(R.id.contactFragment)
                    }
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Setup bottom navigation
        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.homeFragment -> {
                    if (navController.currentDestination?.id != R.id.homeFragment) {
                        if (!navController.popBackStack(R.id.homeFragment, false)) {
                            navController.navigate(R.id.homeFragment)
                        }
                    }
                    true
                }
                R.id.liveEventsFragment -> {
                    if (navController.currentDestination?.id != R.id.liveEventsFragment) {
                        navController.navigate(R.id.liveEventsFragment)
                    }
                    true
                }
                R.id.contactFragment -> {
                    if (navController.currentDestination?.id != R.id.contactFragment) {
                        navController.navigate(R.id.contactFragment)
                    }
                    true
                }
                else -> false
            }
        }

        // Update toolbar title and handle back button vs hamburger menu
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val title = when (destination.id) {
                R.id.homeFragment -> "Categories"
                R.id.categoryChannelsFragment -> "Channels"
                R.id.liveEventsFragment -> "Live Events"
                R.id.favoritesFragment -> "Favorites"
                R.id.contactFragment -> "Contact"
                else -> "Live TV Pro"
            }
            binding.toolbarTitle.text = title
            
            // Show/hide hamburger menu vs back button
            if (destination.id in topLevelDestinations) {
                // Top level - show hamburger menu
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setDisplayShowHomeEnabled(true)
                drawerToggle.isDrawerIndicatorEnabled = true
            } else {
                // Inner page - show back button
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
                supportActionBar?.setDisplayShowHomeEnabled(true)
                drawerToggle.isDrawerIndicatorEnabled = false
                supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
            }
            
            // Update bottom navigation selection
            if (destination.id in topLevelDestinations) {
                binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
            }
            
            // Close drawer after navigation
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        // Favorites button
        binding.btnFavorites.setOnClickListener {
            if (navController.currentDestination?.id != R.id.favoritesFragment) {
                navController.navigate(R.id.favoritesFragment)
            }
        }
    }

    private fun setupDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                currentSearchQuery = query ?: ""
                // Broadcast search query to fragments
                supportFragmentManager.fragments.forEach { fragment ->
                    (fragment as? NavHostFragment)?.childFragmentManager?.fragments?.forEach { childFragment ->
                        when (childFragment) {
                            is com.livetvpro.ui.home.HomeFragment -> {
                                childFragment.view?.post {
                                    // Search will be handled by the fragment's ViewModel
                                }
                            }
                            is com.livetvpro.ui.categories.CategoryChannelsFragment -> {
                                childFragment.view?.post {
                                    // Search will be handled by the fragment's ViewModel
                                }
                            }
                        }
                    }
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                // Real-time search as user types
                return true
            }
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle back button click
        if (item.itemId == android.R.id.home && !drawerToggle.isDrawerIndicatorEnabled) {
            onBackPressed()
            return true
        }
        
        // Handle hamburger menu
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true
        }
        
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    fun getSearchQuery(): String = currentSearchQuery
}
