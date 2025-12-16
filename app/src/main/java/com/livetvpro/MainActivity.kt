package com.livetvpro

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
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
            val isDarkTheme = preferencesManager.isDarkTheme()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            setupToolbar() 
            setupDrawer()  
            setupNavigation()
            setupSearch()
            
        } catch (e: Exception) {
            Timber.e(e, "FATAL: Error in MainActivity onCreate")
            e.printStackTrace()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // FIX: Do not call supportActionBar?.setDisplayHomeAsUpEnabled(true) here.
        // Let the ActionBarDrawerToggle control the icon's visibility and appearance.
    }

    private fun setupDrawer() {
        try {
            drawerToggle = ActionBarDrawerToggle(
                this,
                binding.drawerLayout,
                binding.toolbar,
                R.string.navigation_drawer_open,
                R.string.navigation_drawer_close
            ).apply {
                // Ensure the indicator (the morphing icon) is enabled
                isDrawerIndicatorEnabled = true
                isDrawerSlideAnimationEnabled = true
                syncState()
            }
            
            drawerToggle?.let {
                binding.drawerLayout.addDrawerListener(it)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Error setting up drawer")
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
            
        val navController = navHostFragment.navController

        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.contactFragment
        )

        // FIX: Robust Top-Level Navigation Helper
        val navigateTopLevel = { destinationId: Int ->
            if (navController.currentDestination?.id != destinationId) {
                // Pop all destinations on the back stack until we reach the target, but DO NOT pop the target itself (inclusive=false)
                // If the pop fails (target not on stack), navigate normally.
                if (!navController.popBackStack(destinationId, false)) {
                    navController.navigate(destinationId)
                }
            }
        }

        // Setup drawer navigation
        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            if (menuItem.itemId in topLevelDestinations) {
                navigateTopLevel(menuItem.itemId)
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Setup bottom navigation
        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            if (menuItem.itemId in topLevelDestinations) {
                navigateTopLevel(menuItem.itemId)
            }
            true
        }

        // MAIN NAVIGATION LOGIC (Icon/Lock/Title updates)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            
            // Set Title
            binding.toolbarTitle.text = when (destination.id) {
                R.id.homeFragment -> "Categories"
                R.id.categoryChannelsFragment -> "Channels"
                R.id.liveEventsFragment -> "Live Events"
                R.id.favoritesFragment -> "Favorites"
                R.id.contactFragment -> "Contact"
                else -> "Live TV Pro"
            }
            
            val isTopLevel = destination.id in topLevelDestinations
            
            // Handle Click Actions & Drawer Locking
            if (isTopLevel) {
                // Top Level: Unlock drawer
                binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                
                // Set the indicator progress to Hamburger (0f)
                animateNavigationIcon(0f) 

                // Click Action: Open/Close Drawer 
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
                // Deeper Level: Lock drawer closed
                binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                
                // Set the indicator progress to Arrow (1f)
                animateNavigationIcon(1f)
                
                // Click Action: Go Back
                binding.toolbar.setNavigationOnClickListener {
                    onBackPressedDispatcher.onBackPressed()
                }
            }
            
            // Hide search if open
            if (isSearchVisible) {
                hideSearch()
            }
        }

        binding.btnFavorites.setOnClickListener {
            if (navController.currentDestination?.id != R.id.favoritesFragment) {
                navController.navigate(R.id.favoritesFragment)
            }
        }
    }

    private fun setupSearch() {
        binding.searchView.visibility = View.GONE
        
        binding.btnSearch.setOnClickListener {
            showSearch()
        }
        
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText ?: ""
                // FIX: Cross icon visibility while typing
                binding.btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                // Live search to fragment
                val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                
                if (currentFragment is SearchableFragment) {
                    currentFragment.onSearchQuery(query)
                }
                return true
            }
        })
        
        binding.btnSearchClear.setOnClickListener {
            binding.searchView.setQuery("", false)
        }
    }

    private fun showSearch() {
        isSearchVisible = true
        
        binding.toolbarTitle.visibility = View.GONE
        binding.btnSearch.visibility = View.GONE
        binding.btnFavorites.visibility = View.GONE
        
        binding.searchView.visibility = View.VISIBLE
        binding.searchView.isIconified = false
        binding.searchView.requestFocus()
        
        // Animate to Back Arrow
        animateNavigationIcon(1f)
        
        // Override click to close search
        binding.toolbar.setNavigationOnClickListener {
            hideSearch()
        }
    }

    private fun hideSearch() {
        isSearchVisible = false
        
        binding.toolbarTitle.visibility = View.VISIBLE
        binding.btnSearch.visibility = View.VISIBLE
        binding.btnFavorites.visibility = View.VISIBLE
        
        binding.searchView.visibility = View.GONE
        binding.btnSearchClear.visibility = View.GONE
        binding.searchView.setQuery("", false)
        binding.searchView.clearFocus()
        
        // RESTORE NAVIGATION STATE
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentId = navHostFragment?.navController?.currentDestination?.id
        
        val topLevelDestinations = setOf(R.id.homeFragment, R.id.liveEventsFragment, R.id.contactFragment)
        val isTopLevel = currentId in topLevelDestinations

        // Restore correct icon state (Hamburger or Arrow)
        animateNavigationIcon(if (isTopLevel) 0f else 1f)

        // Restore correct listener
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
    }

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
