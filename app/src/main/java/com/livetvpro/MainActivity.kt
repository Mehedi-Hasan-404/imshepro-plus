package com.livetvpro

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
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
    
    private val indicator by lazy { binding.bottomNavIndicator } 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            Timber.d("============================================")
            Timber.d("MainActivity onCreate() started")
            Timber.d("============================================")
            
            Timber.d("Step 1: Setting theme...")
            try {
                val isDarkTheme = preferencesManager.isDarkTheme()
                AppCompatDelegate.setDefaultNightMode(
                    if (isDarkTheme) AppCompatDelegate.MODE_NIGHT_YES 
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
                Timber.d("✅ Theme set: ${if (isDarkTheme) "Dark" else "Light"}")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error setting theme (using default)")
            }

            Timber.d("Step 2: Inflating layout...")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Timber.d("✅ Layout inflated successfully")
            
            Timber.d("Step 3: Setting up toolbar...")
            setupToolbar() 
            Timber.d("✅ Toolbar setup complete")
            
            Timber.d("Step 4: Setting up drawer...")
            setupDrawer()
            Timber.d("✅ Drawer setup complete")
            
            Timber.d("Step 5: Setting up navigation...")
            setupNavigation()
            Timber.d("✅ Navigation setup complete")
            
            Timber.d("Step 6: Setting up search...")
            setupSearch()
            Timber.d("✅ Search setup complete")
            
            Timber.d("============================================")
            Timber.d("MainActivity onCreate() completed successfully")
            Timber.d("============================================")
            
            Toast.makeText(this, "MainActivity loaded successfully", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Timber.e(e, "❌❌❌ FATAL: Error in MainActivity onCreate ❌❌❌")
            Timber.e("Error: ${e.message}")
            Timber.e("Stack trace: ${e.stackTraceToString()}")
            
            Toast.makeText(this, "MainActivity Error: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun setupToolbar() {
        try {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            Timber.d("Toolbar configured")
        } catch (e: Exception) {
            Timber.e(e, "Error in setupToolbar")
            throw e
        }
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
            
            Timber.d("Drawer configured")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up drawer")
        }
    }

    private fun setupNavigation() {
        try {
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            
            if (navHostFragment == null) {
                Timber.e("❌ NavHostFragment is null!")
                Toast.makeText(this, "Navigation setup failed", Toast.LENGTH_LONG).show()
                return
            }
            
            val navController = navHostFragment.navController
            Timber.d("NavController obtained")

            val topLevelDestinations = setOf(
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.contactFragment
            )
            
            val graphStartDestinationId = navController.graph.startDestinationId 

            val navigateTopLevel = { destinationId: Int ->
                val currentId = navController.currentDestination?.id ?: graphStartDestinationId
                
                if (currentId != destinationId) {
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(currentId, true) 
                        .setLaunchSingleTop(true) 
                        .setRestoreState(true) 
                        .build()
                    
                    navController.navigate(destinationId, null, navOptions)
                }
            }

            binding.navigationView.setNavigationItemSelectedListener { menuItem ->
                if (menuItem.itemId in topLevelDestinations) {
                    navigateTopLevel(menuItem.itemId)
                }
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                true
            }

            binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
                if (menuItem.itemId in topLevelDestinations) {
                    navigateTopLevel(menuItem.itemId)
                    
                    val selectedItemView = binding.bottomNavigation.findViewById<View>(menuItem.itemId)
                    animateBottomNavItem(selectedItemView)
                    
                    return@setOnItemSelectedListener true
                }
                return@setOnItemSelectedListener false
            }
            
            binding.bottomNavigation.post { setupIndicator(navController.currentDestination?.id) }

            navController.addOnDestinationChangedListener { _, destination, _ ->
                
                binding.toolbarTitle.text = when (destination.id) {
                    R.id.homeFragment -> "Categories"
                    R.id.categoryChannelsFragment -> "Channels"
                    R.id.liveEventsFragment -> "Live Events"
                    R.id.favoritesFragment -> "Favorites"
                    R.id.contactFragment -> "Contact"
                    else -> "Live TV Pro"
                }
                
                val isTopLevel = destination.id in topLevelDestinations
                
                if (isTopLevel) {
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                    
                    animateNavigationIcon(0f) 

                    binding.toolbar.setNavigationOnClickListener {
                        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            binding.drawerLayout.closeDrawer(GravityCompat.START)
                        } else {
                            binding.drawerLayout.openDrawer(GravityCompat.START)
                        }
                    }
                    
                    binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
                    
                    val currentView = binding.bottomNavigation.findViewById<View>(destination.id)
                    animateBottomNavItem(currentView)

                } else {
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    
                    animateNavigationIcon(1f)
                    
                    binding.toolbar.setNavigationOnClickListener {
                        onBackPressedDispatcher.onBackPressed()
                    }
                    
                    if (lastSelectedView != null) {
                        lastSelectedView?.animate()
                            ?.scaleX(1.0f)
                            ?.scaleY(1.0f)
                            ?.translationY(0f)
                            ?.setDuration(150)
                            ?.start()
                        lastSelectedView = null
                    }
                    indicator.animate().alpha(0f).setDuration(150).start()
                }
                
                if (isSearchVisible) {
                    hideSearch()
                }
            }

            binding.btnFavorites.setOnClickListener {
                if (navController.currentDestination?.id != R.id.favoritesFragment) {
                    navController.navigate(R.id.favoritesFragment)
                }
            }
            
            Timber.d("Navigation listeners configured")
            
        } catch (e: Exception) {
            Timber.e(e, "Error in setupNavigation")
            throw e
        }
    }

    private fun setupIndicator(destinationId: Int?) {
        try {
            val initialItemId = destinationId ?: binding.bottomNavigation.menu.getItem(0).itemId 
            val initialView = binding.bottomNavigation.findViewById<View>(initialItemId)

            if (initialView != null) {
                val initialX = initialView.x + initialView.width * 0.25f
                val initialWidth = initialView.width * 0.5f
                
                indicator.translationX = initialX
                indicator.layoutParams.width = initialWidth.toInt()
                indicator.requestLayout()
                indicator.alpha = 1f
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in setupIndicator")
        }
    }
    
    private fun animateBottomNavItem(newSelectedView: View?) {
        try {
            if (newSelectedView == null || lastSelectedView == newSelectedView) return
            
            val duration = 300L 
            
            indicator.alpha = 1f
            
            val targetX = newSelectedView.x + newSelectedView.width * 0.25f 
            val targetWidth = newSelectedView.width * 0.5f 

            indicator.animate()
                .translationX(targetX)
                .setDuration(duration)
                .start()

            val widthAnimator = ValueAnimator.ofInt(indicator.width, targetWidth.toInt())
            widthAnimator.addUpdateListener { animator ->
                indicator.layoutParams.width = animator.animatedValue as Int
                indicator.requestLayout()
            }
            widthAnimator.duration = duration
            widthAnimator.start()

            lastSelectedView?.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.translationY(0f) 
                ?.setDuration(duration)
                ?.start()

            newSelectedView.animate()
                ?.scaleX(1.15f) 
                ?.scaleY(1.15f)
                ?.translationY(-8f)
                ?.setDuration(duration)
                ?.start()
                
            lastSelectedView = newSelectedView
        } catch (e: Exception) {
            Timber.e(e, "Error in animateBottomNavItem")
        }
    }

    private fun setupSearch() {
        try {
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
        } catch (e: Exception) {
            Timber.e(e, "Error in setupSearch")
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

    private fun animateNavigationIcon(endPosition: Float) {
        try {
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
        } catch (e: Exception) {
            Timber.e(e, "Error in animateNavigationIcon")
        }
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
