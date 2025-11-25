package com.livetvpro

import android.os.Bundle
import android.view.MenuItem
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

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    @Inject
    lateinit var preferencesManager: PreferencesManager
    
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var currentSearchQuery: String = ""

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
            
            Timber.d("Binding inflated successfully")

            setupToolbar()
            setupNavigation()
            setupDrawer()
            setupSearch()
            
            Timber.d("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "FATAL: Error in MainActivity onCreate")
            e.printStackTrace()
            
            Toast.makeText(
                this,
                "Error starting app: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupToolbar() {
        try {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            Timber.d("Toolbar setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up toolbar")
            throw e
        }
    }

    private fun setupNavigation() {
        try {
            Timber.d("Setting up navigation")
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                
            if (navHostFragment == null) {
                Timber.e("NavHostFragment not found!")
                Toast.makeText(this, "Navigation error - Fragment not found", Toast.LENGTH_LONG).show()
                return
            }
            
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
                
                // Show/hide hamburger menu vs back button with animation
                val isTopLevel = destination.id in topLevelDestinations
                
                if (isTopLevel) {
                    // Show hamburger menu (drawer indicator)
                    drawerToggle?.isDrawerIndicatorEnabled = true
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    drawerToggle?.syncState()
                    
                    // Lock drawer (enable swipe to open)
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
                } else {
                    // Show back arrow
                    drawerToggle?.isDrawerIndicatorEnabled = false
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
                    
                    // Lock drawer (disable swipe to open on sub-screens)
                    binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                    
                    // Set toolbar navigation click listener for back button
                    binding.toolbar.setNavigationOnClickListener {
                        navController.navigateUp()
                    }
                }
                
                // Update bottom navigation selection
                if (isTopLevel) {
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
            
            Timber.d("Navigation setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up navigation")
            Toast.makeText(this, "Navigation setup failed: ${e.message}", Toast.LENGTH_LONG).show()
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
                syncState()
            }
            
            drawerToggle?.let {
                binding.drawerLayout.addDrawerListener(it)
            }
            
            Timber.d("Drawer setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up drawer")
            Toast.makeText(this, "Drawer setup failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearch() {
        try {
            binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    currentSearchQuery = query ?: ""
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    currentSearchQuery = newText ?: ""
                    return true
                }
            })
            Timber.d("Search setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up search")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Let drawer toggle handle it if enabled
        if (drawerToggle?.isDrawerIndicatorEnabled == true) {
            if (drawerToggle?.onOptionsItemSelected(item) == true) {
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val navController = navHostFragment?.navController
        return navController?.navigateUp() ?: false || super.onSupportNavigateUp()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle?.syncState()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle?.onConfigurationChanged(newConfig)
    }

    fun getSearchQuery(): String = currentSearchQuery
}
