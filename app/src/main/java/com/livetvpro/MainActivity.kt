package com.livetvpro

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

// Interface to pass search queries to fragments
interface SearchableFragment {
    fun onSearchQuery(query: String)
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var preferencesManager: PreferencesManager
    
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var isSearchVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            Timber.d("MainActivity onCreate started")
            
            // Apply saved theme
            val isDarkTheme = preferencesManager.isDarkTheme()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            setupToolbar()
            setupDrawer() // Setup drawer first
            setupNavigation()
            setupSearch()
            
            Timber.d("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "FATAL: Error in MainActivity onCreate")
            e.printStackTrace()
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupDrawer() {
        try {
            drawerToggle = ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
            )
            // Note: We don't rely on syncState() here for the icon logic anymore
            // because we are animating it manually in setupNavigation()
            binding.drawerLayout.addDrawerListener(drawerToggle!!)
            
            Timber.d("Drawer setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up drawer")
        }
    }

    private fun setupNavigation() {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                
            if (navHostFragment == null) {
                Timber.e("NavHostFragment not found!")
                return
            }
            
            val navController = navHostFragment.navController

            val topLevelDestinations = setOf(
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.contactFragment
            )

            // Drawer Item Click
            binding.navigationView.setNavigationItemSelectedListener { menuItem ->
                if (menuItem.itemId != navController.currentDestination?.id) {
                    navController.navigate(menuItem.itemId)
                }
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                true
            }

            // Bottom Nav Click
            binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
                if (menuItem.itemId != navController.currentDestination?.id) {
                    // Simple navigation, avoiding complex stack logic for now to prevent bugs
                    navController.navigate(menuItem.itemId)
                }
                true
            }

            // NAVIGATION LISTENER: Handles Icon Animation and Toolbar Clicks
            navController.addOnDestinationChangedListener { _, destination, _ ->
                // 1. Update Title
                binding.toolbarTitle.text = when (destination.id) {
                    R.id.homeFragment -> "Categories"
                    R.id.categoryChannelsFragment -> "Channels"
                    R.id.liveEventsFragment -> "Live Events"
                    R.id.favoritesFragment -> "Favorites"
                    R.id.contactFragment -> "Contact"
                    else -> "Live TV Pro"
                }
                
                val isTopLevel = destination.id in topLevelDestinations
                
                // 2. Animate Hamburger (0f) <-> Back Arrow (1f)
                val targetProgress = if (isTopLevel) 0f else 1f
                animateNavigationIcon(targetProgress)
                
                // 3. Set Drawer Lock Mode and Navigation Listener
                if (isTopLevel) {
                    // Unlock Drawer
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                    
                    // Click opens Drawer
                    binding.toolbar.setNavigationOnClickListener {
                        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            binding.drawerLayout.closeDrawer(GravityCompat.START)
                        } else {
                            binding.drawerLayout.openDrawer(GravityCompat.START)
                        }
                    }
                    
                    // Sync Bottom Nav
                    binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true

                } else {
                    // Lock Drawer Closed
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    
                    // Click goes Back
                    binding.toolbar.setNavigationOnClickListener {
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
                
                // 4. Reset Search if visible
                if (isSearchVisible) {
                    hideSearch()
                }
            }

            // Favorites button
            binding.btnFavorites.setOnClickListener {
                if (navController.currentDestination?.id != R.id.favoritesFragment) {
                    navController.navigate(R.id.favoritesFragment)
                }
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error setting up navigation")
        }
    }

    private fun setupSearch() {
        binding.searchView.visibility = View.GONE
        
        // Open Search
        binding.btnSearch.setOnClickListener {
            showSearch()
        }
        
        // Search Listeners
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                
                // Toggle 'X' button based on text
                binding.btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                // Pass query to current fragment
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                
                if (currentFragment is SearchableFragment) {
                    currentFragment.onSearchQuery(query)
                }
                
                return true
            }
        })
        
        // Clear Button
        binding.btnSearchClear.setOnClickListener {
            binding.searchView.setQuery("", false)
        }
    }

    private fun showSearch() {
        isSearchVisible = true
        
        // Hide standard items
        binding.toolbarTitle.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
        binding.btnFavorites.visibility = View.GONE
        
        // Show search items
        binding.searchView.visibility = View.VISIBLE
        // btnSearchClear is handled by text listener
        
        // Focus
        binding.searchView.isIconified = false
        binding.searchView.requestFocus()
        
        // Animate icon to Back Arrow (1f)
        animateNavigationIcon(1f)
        
        // Override Back Navigation to Close Search
        binding.toolbar.setNavigationOnClickListener {
            hideSearch()
        }
        
        Timber.d("Search mode activated")
    }

    private fun hideSearch() {
        isSearchVisible = false
        
        // Restore standard items
        binding.toolbarTitle.visibility = View.VISIBLE
        binding.btnSearch.visibility = View.VISIBLE
        binding.btnFavorites.visibility = View.VISIBLE
        
        // Hide search items
        binding.searchView.visibility = View.GONE
        binding.btnSearchClear.visibility = View.GONE
        
        // Clear query
        binding.searchView.setQuery("", false)
        binding.searchView.clearFocus()
        
        // RESTORE NAVIGATION ICON AND LISTENER
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentId = navHostFragment?.navController?.currentDestination?.id
        
        val topLevelDestinations = setOf(R.id.homeFragment, R.id.liveEventsFragment, R.id.contactFragment)
        val isTopLevel = currentId in topLevelDestinations

        // Animate back to Hamburger (0f) if top level, stay Arrow (1f) if deep
        animateNavigationIcon(if (isTopLevel) 0f else 1f)

        if (isTopLevel) {
            binding.toolbar.setNavigationOnClickListener {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    binding.drawerLayout.openDrawer(GravityCompat.START)
                }
            }
        } else {
            binding.toolbar.setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        
        Timber.d("Search mode deactivated")
    }

    /**
     * Animates the Navigation Icon (Hamburger <-> Arrow)
     * 0f = Hamburger
     * 1f = Arrow
     */
    private fun animateNavigationIcon(endPosition: Float) {
        val startPosition = drawerToggle?.drawerArrowDrawable?.progress ?: 0f
        if (startPosition == endPosition) return

        val animator = ValueAnimator.ofFloat(startPosition, endPosition)
        animator.addUpdateListener { valueAnimator ->
            val slideOffset = valueAnimator.animatedValue as Float
            drawerToggle?.drawerArrowDrawable?.progress = slideOffset
        }
        animator.interpolator = DecelerateInterpolator()
        animator.duration = 300
        animator.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            isSearchVisible -> hideSearch()
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> binding.drawerLayout.closeDrawer(GravityCompat.START)
            else -> super.onBackPressed()
        }
    }
}
