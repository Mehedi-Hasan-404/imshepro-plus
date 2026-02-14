package com.livetvpro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.databinding.ActivityMainBinding
import com.livetvpro.ui.player.dialogs.FloatingPlayerDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

interface SearchableFragment {
    fun onSearchQuery(query: String)
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration

    @Inject lateinit var preferencesManager: PreferencesManager
    @Inject lateinit var themeManager: ThemeManager

    private var isSearchVisible = false

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeManager.applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupNavigation()
        setupSearch()
    }

    // --------------------------------------------------
    // Navigation (FIXED — uses NavigationUI properly)
    // --------------------------------------------------

    private fun setupNavigation() {

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.sportsFragment
        )

        appBarConfiguration = AppBarConfiguration(
            topLevelDestinations,
            binding.drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)

        // Drawer menu clicks
        binding.navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {

                R.id.floating_player_settings -> {
                    showFloatingPlayerDialog()
                }

                else -> navController.navigate(item.itemId)
            }

            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Bottom nav clicks
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            navController.navigate(item.itemId)
            true
        }

        // Destination changes
        navController.addOnDestinationChangedListener { _, destination, _ ->

            binding.toolbarTitle.text = when (destination.id) {
                R.id.homeFragment -> "Categories"
                R.id.categoryChannelsFragment -> "Channels"
                R.id.liveEventsFragment -> "Live Events"
                R.id.favoritesFragment -> "Favorites"
                R.id.sportsFragment -> "Sports"
                R.id.contactFragment -> "Contact"
                R.id.networkStreamFragment -> "Network Stream"
                else -> "Live TV Pro"
            }

            val isNetworkStream = destination.id == R.id.networkStreamFragment

            if (isNetworkStream) {
                binding.bottomNavigation.visibility = View.GONE
                binding.btnSearch.visibility = View.GONE
                binding.btnFavorites.visibility = View.GONE
            } else {
                binding.bottomNavigation.visibility = View.VISIBLE
                binding.btnSearch.visibility = View.VISIBLE
                binding.btnFavorites.visibility = View.VISIBLE
            }

            if (isSearchVisible) hideSearch()
        }

        binding.btnFavorites.setOnClickListener {
            navController.navigate(R.id.favoritesFragment)
        }
    }

    // --------------------------------------------------
    // Floating Player Permission
    // --------------------------------------------------

    private fun showFloatingPlayerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Floating Player needs overlay permission.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ),
                        REQUEST_CODE_OVERLAY_PERMISSION
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            FloatingPlayerDialog.newInstance()
                .show(supportFragmentManager, FloatingPlayerDialog.TAG)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)
        ) {
            FloatingPlayerDialog.newInstance()
                .show(supportFragmentManager, FloatingPlayerDialog.TAG)
        }
    }

    // --------------------------------------------------
    // Search
    // --------------------------------------------------

    private fun setupSearch() {

        binding.btnSearch.setOnClickListener { showSearch() }

        binding.searchView.setOnQueryTextListener(
            object : androidx.appcompat.widget.SearchView.OnQueryTextListener {

                override fun onQueryTextSubmit(query: String?) = true

                override fun onQueryTextChange(newText: String?): Boolean {

                    val navHost =
                        supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                                as NavHostFragment

                    val fragment =
                        navHost.childFragmentManager.fragments.firstOrNull()

                    if (fragment is SearchableFragment) {
                        fragment.onSearchQuery(newText.orEmpty())
                    }

                    binding.btnSearchClear.visibility =
                        if (newText.isNullOrEmpty()) View.GONE else View.VISIBLE

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
    }

    private fun hideSearch() {
        isSearchVisible = false

        binding.toolbarTitle.visibility = View.VISIBLE
        binding.btnSearch.visibility = View.VISIBLE
        binding.btnFavorites.visibility = View.VISIBLE

        binding.searchView.setQuery("", false)
        binding.searchView.clearFocus()
        binding.searchView.visibility = View.GONE
        binding.btnSearchClear.visibility = View.GONE
    }

    // --------------------------------------------------
    // Proper back handling (NavigationUI)
    // --------------------------------------------------

    override fun onSupportNavigateUp(): Boolean {
        val navHost =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    as NavHostFragment
        return navHost.navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    override fun onBackPressed() {
        when {
            isSearchVisible -> hideSearch()
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            else -> super.onBackPressed()
        }
    }
}
