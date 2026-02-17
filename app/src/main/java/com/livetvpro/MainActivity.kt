package com.livetvpro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.databinding.ActivityMainBinding
import com.livetvpro.ui.player.dialogs.FloatingPlayerDialog
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var themeManager: ThemeManager

    private var drawerToggle: ActionBarDrawerToggle? = null
    private var currentSearchQuery: String = ""

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            Timber.d("MainActivity onCreate started")

            themeManager.applyTheme()

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            window.statusBarColor = android.graphics.Color.BLACK

            setupToolbar()
            setupNavigation()
            setupDrawer()
            setupSearch()

            Timber.d("MainActivity onCreate completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "FATAL: Error in MainActivity onCreate")
            e.printStackTrace()
            Toast.makeText(this, "Error starting app: ${e.message}", Toast.LENGTH_LONG).show()
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

            val topLevelDestinations = setOf(
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.sportsFragment
            )

            // Drawer navigation
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
                    R.id.sportsFragment -> {
                        if (navController.currentDestination?.id != R.id.sportsFragment) {
                            navController.navigate(R.id.sportsFragment)
                        }
                    }
                    R.id.contactFragment -> {
                        if (navController.currentDestination?.id != R.id.contactFragment) {
                            navController.navigate(R.id.contactFragment)
                        }
                    }
                    R.id.networkStreamFragment -> {
                        if (navController.currentDestination?.id != R.id.networkStreamFragment) {
                            navController.navigate(R.id.networkStreamFragment)
                        }
                    }
                    R.id.playlistsFragment -> {
                        if (navController.currentDestination?.id != R.id.playlistsFragment) {
                            navController.navigate(R.id.playlistsFragment)
                        }
                    }
                    R.id.floating_player_settings -> {
                        showFloatingPlayerDialog()
                    }
                }
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                true
            }

            // Bottom navigation
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
                    R.id.sportsFragment -> {
                        if (navController.currentDestination?.id != R.id.sportsFragment) {
                            navController.navigate(R.id.sportsFragment)
                        }
                        true
                    }
                    else -> false
                }
            }

            // Destination change listener
            navController.addOnDestinationChangedListener { _, destination, _ ->
                binding.toolbarTitle.text = when (destination.id) {
                    R.id.homeFragment -> "Categories"
                    R.id.categoryChannelsFragment -> "Channels"
                    R.id.liveEventsFragment -> "Live Events"
                    R.id.favoritesFragment -> "Favorites"
                    R.id.sportsFragment -> "Sports"
                    R.id.contactFragment -> "Contact"
                    R.id.networkStreamFragment -> "Network Stream"
                    R.id.playlistsFragment -> "Playlists"
                    else -> "Live TV Pro"
                }

                val isTopLevel = destination.id in topLevelDestinations
                val isNetworkStream = destination.id == R.id.networkStreamFragment

                if (isTopLevel) {
                    drawerToggle?.isDrawerIndicatorEnabled = true
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    drawerToggle?.syncState()
                    binding.drawerLayout.setDrawerLockMode(
                        androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                    )
                    binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
                } else {
                    drawerToggle?.isDrawerIndicatorEnabled = false
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back)
                    binding.drawerLayout.setDrawerLockMode(
                        androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                    )
                    binding.toolbar.setNavigationOnClickListener {
                        navController.navigateUp()
                    }
                }

                binding.btnFavorites.visibility = if (isNetworkStream) View.GONE else View.VISIBLE

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

    private fun showFloatingPlayerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Floating Player requires permission to draw over other apps. Please enable it in the next screen.")
                .setPositiveButton("Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            FloatingPlayerDialog.newInstance().show(supportFragmentManager, FloatingPlayerDialog.TAG)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
            @Suppress("DEPRECATION")
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                FloatingPlayerDialog.newInstance().show(supportFragmentManager, FloatingPlayerDialog.TAG)
            }
        }
    }

    fun getSearchQuery(): String = currentSearchQuery
}
