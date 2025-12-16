package com.livetvpro

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.navigation.NavOptions
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
    private var lastSelectedView: View? = null 
    
    // NEW: Reference to the sliding indicator view
    private val indicator by lazy { binding.bottomNavIndicator } 

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
        
        val graphStartDestinationId = navController.graph.startDestinationId 

        // Helper function for navigating to a top-level screen
        val navigateTopLevel = { destinationId: Int ->
            val currentId = navController.currentDestination?.id ?: graphStartDestinationId
            
            if (currentId != destinationId) {
                // The CRITICAL NavOptions for robust state preservation:
                val navOptions = NavOptions.Builder()
                    // 1. Pop up to the currently active destination *inclusively*. This clears the
                    //    transient back stack of the tab we are leaving.
                    .setPopUpTo(currentId, true) 
                    .setLaunchSingleTop(true) 
                    // 2. CRUCIAL: Saves the stack state we're leaving and restores the stack state we're entering.
                    .setRestoreState(true) 
                    .build()
                
                navController.navigate(destinationId, null, navOptions)
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
                
                // Trigger the enhanced animation (scale, translation, and indicator slide)
                val selectedItemView = binding.bottomNavigation.findViewById<View>(menuItem.itemId)
                animateBottomNavItem(selectedItemView)
                
                return@setOnItemSelectedListener true
            }
            return@setOnItemSelectedListener false
        }
        
        // NEW: Setup initial indicator position after the view is drawn
        binding.bottomNavigation.post { setupIndicator(navController.currentDestination?.id) }


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
                
                // Sync Bottom Nav selection state
                binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
                
                // Animate icon and indicator on destination change (e.g., via back button)
                val currentView = binding.bottomNavigation.findViewById<View>(destination.id)
                animateBottomNavItem(currentView)

            } else {
                binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                
                // Set the indicator progress to Arrow (1f)
                animateNavigationIcon(1f)
                
                // Click Action: Go Back
                binding.toolbar.setNavigationOnClickListener {
                    onBackPressedDispatcher.onBackPressed()
                }
                
                // Deselect and shrink bottom nav item when navigating away from top level
                if (lastSelectedView != null) {
                    lastSelectedView?.animate()
                        ?.scaleX(1.0f)
                        ?.scaleY(1.0f)
                        ?.translationY(0f) // Reset translation
                        ?.setDuration(150)
                        ?.start()
                    lastSelectedView = null
                }
                // Also hide the indicator when navigating to a non-top-level destination
                indicator.animate().alpha(0f).setDuration(150).start()
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

    /**
     * Initializes the indicator's position and size on startup.
     */
    private fun setupIndicator(destinationId: Int?) {
        val initialItemId = destinationId ?: binding.bottomNavigation.menu.getItem(0).itemId 
        val initialView = binding.bottomNavigation.findViewById<View>(initialItemId)

        if (initialView != null) {
            // Calculate starting position and size
            val initialX = initialView.x + initialView.width * 0.25f // 25% margin from left
            val initialWidth = initialView.width * 0.5f // Half the item width
            
            indicator.translationX = initialX
            indicator.layoutParams.width = initialWidth.toInt()
            indicator.requestLayout()
            indicator.alpha = 1f // Ensure it's visible
        }
    }
    
    /**
     * ENHANCED: Applies a sliding indicator animation, scaling, and vertical translation.
     */
    private fun animateBottomNavItem(newSelectedView: View?) {
        // Do not animate if the view is null or the same view is selected
        if (newSelectedView == null || lastSelectedView == newSelectedView) return
        
        val duration = 300L 
        
        // 1. Indicator Animation (Smooth Slide)
        indicator.alpha = 1f // Ensure it becomes visible when returning to top level
        
        // Target position (centered pill)
        val targetX = newSelectedView.x + newSelectedView.width * 0.25f 
        val targetWidth = newSelectedView.width * 0.5f 

        // Animate Indicator Position (TranslationX)
        indicator.animate()
            .translationX(targetX)
            .setDuration(duration)
            .start()

        // Animate Indicator Width (more complex, requires ValueAnimator)
        val widthAnimator = ValueAnimator.ofInt(indicator.width, targetWidth.toInt())
        widthAnimator.addUpdateListener { animator ->
            indicator.layoutParams.width = animator.animatedValue as Int
            indicator.requestLayout()
        }
        widthAnimator.duration = duration
        widthAnimator.start()


        // 2. Icon Animation (Vertical translation + Scale)

        // Descale and Translate Down the previously selected item (Reset to default)
        lastSelectedView?.animate()
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.translationY(0f) 
            ?.setDuration(duration)
            ?.start()

        // Scale up and Translate Up the newly selected item (Highlight)
        newSelectedView.animate()
            ?.scaleX(1.15f) 
            ?.scaleY(1.15f)
            ?.translationY(-8f) // Move up slightly
            ?.setDuration(duration)
            ?.start()
            
        // 3. Update the reference
        lastSelectedView = newSelectedView
    }

    // ... (setupSearch, showSearch, hideSearch, animateNavigationIcon remain the same) ...

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
                binding.btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
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
        
        animateNavigationIcon(1f)
        
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
    }

    /**
     * Animates the Navigation Icon (Hamburger <-> Arrow)
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
