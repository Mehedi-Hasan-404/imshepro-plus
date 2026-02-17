package com.livetvpro

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.databinding.ActivityMainBinding
import com.livetvpro.ui.player.dialogs.FloatingPlayerDialog
import com.livetvpro.utils.Refreshable
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

    @Inject
    lateinit var themeManager: ThemeManager

    private var drawerToggle: ActionBarDrawerToggle? = null
    private var isSearchVisible = false
    private var showRefreshIcon = false

    /** True when running on Android TV / Google TV. Set from values-television/booleans.xml */
    private var isTvDevice = false

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
        const val EXTRA_IS_FIRE_TV = "extra_is_fire_tv"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeManager.applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Detect TV via resource qualifier (values-television/booleans.xml)
        // OR via TV launcher intent category passed from SplashActivity
        val isFireTv = intent.getBooleanExtra(EXTRA_IS_FIRE_TV, false)
        isTvDevice = resources.getBoolean(R.bool.is_tv_device) || isFireTv

        if (isTvDevice) {
            // TV: full-screen, no status bar chrome needed
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
            setupTvNavigation()
        } else {
            handleStatusBarForOrientation()
            setupToolbar()
            setupDrawer()
            setupNavigation()
            setupSearch()
        }
    }

    private fun handleStatusBarForOrientation() {
        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isLandscape) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            // Enforce black status bar in portrait regardless of active theme
            window.statusBarColor = android.graphics.Color.BLACK
            windowInsetsController.isAppearanceLightStatusBars = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle?.onConfigurationChanged(newConfig)
        if (!isTvDevice) handleStatusBarForOrientation()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TV NAVIGATION  (Google TV-style top tab bar)
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupTvNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        val navController = navHostFragment.navController

        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        )
        val navigationView = binding.root.findViewById<com.google.android.material.navigation.NavigationView>(
            R.id.navigation_view
        )

        // Map tab view IDs → destination IDs
        val tabDestinations = mapOf(
            R.id.tv_tab_live      to R.id.liveEventsFragment,
            R.id.tv_tab_home      to R.id.homeFragment,
            R.id.tv_tab_sports    to R.id.sportsFragment,
            R.id.tv_tab_favorites to R.id.favoritesFragment
        )

        fun selectTab(selectedDestId: Int) {
            tabDestinations.forEach { (viewId, destId) ->
                val tab = binding.root.findViewById<android.widget.TextView>(viewId) ?: return@forEach
                tab.isSelected = destId == selectedDestId
            }
        }

        fun navigate(destinationId: Int) {
            val currentId = navController.currentDestination?.id ?: return
            if (currentId == destinationId) return
            val navOptions = NavOptions.Builder()
                .setPopUpTo(navController.graph.startDestinationId, false)
                .setLaunchSingleTop(true)
                .build()
            navController.navigate(destinationId, null, navOptions)
        }

        // Wire tab click listeners
        tabDestinations.forEach { (viewId, destId) ->
            binding.root.findViewById<android.widget.TextView>(viewId)?.setOnClickListener {
                navigate(destId)
            }
        }

        // Avatar opens the drawer (like the profile avatar in Google TV)
        binding.root.findViewById<android.widget.ImageView>(R.id.tv_avatar)
            ?.setOnClickListener {
                drawerLayout?.openDrawer(GravityCompat.START)
            }

        // Wire NavigationView item selection (same logic as phone)
        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.sportsFragment
        )
        navigationView?.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.floating_player_settings -> {
                    showFloatingPlayerDialog()
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.contactFragment, R.id.networkStreamFragment, R.id.playlistsFragment -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    drawerLayout?.postDelayed({
                        navController.navigate(menuItem.itemId)
                    }, 250)
                    true
                }
                else -> {
                    if (menuItem.itemId in topLevelDestinations) navigate(menuItem.itemId)
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    true
                }
            }
        }

        // Sync tab highlight when destination changes
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val activeDestId = when (destination.id) {
                R.id.categoryChannelsFragment -> R.id.homeFragment
                else -> destination.id
            }
            selectTab(activeDestId)
        }

        // Select start tab on launch
        selectTab(navController.graph.startDestinationId)

        // Wire search button
        binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search)
            ?.setOnClickListener { showTvSearch() }

        // Wire search view
        val tvSearchView = binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        val tvClearBtn = binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search_clear)

        tvSearchView?.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { query ->
                    val nhf = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    val frag = nhf?.childFragmentManager?.fragments?.firstOrNull()
                    if (frag is SearchableFragment) frag.onSearchQuery(query)
                    tvClearBtn?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                }
                return true
            }
        })

        tvClearBtn?.setOnClickListener { tvSearchView?.setQuery("", false) }
    }

    private fun showTvSearch() {
        isSearchVisible = true
        val tvSearchBar = binding.root.findViewById<android.view.View>(R.id.tv_search_bar)
        tvSearchBar?.visibility = View.VISIBLE
        val tvSearchView = binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        tvSearchView?.isIconified = false
        tvSearchView?.requestFocus()
    }

    private fun hideTvSearch() {
        isSearchVisible = false
        val tvSearchBar = binding.root.findViewById<android.view.View>(R.id.tv_search_bar)
        tvSearchBar?.visibility = View.GONE
        val tvSearchView = binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        tvSearchView?.setQuery("", false)
        tvSearchView?.clearFocus()
        binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search_clear)
            ?.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // TV has no options menu / toolbar overflow
        if (isTvDevice) return false
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (isTvDevice) return false
        menu.findItem(R.id.action_refresh)?.isVisible = showRefreshIcon
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                val navHostFragment = supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                val currentFragment =
                    navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                if (currentFragment is Refreshable) {
                    currentFragment.refreshData()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupDrawer() {
        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        ) ?: return

        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        ).apply {
            isDrawerIndicatorEnabled = true
            isDrawerSlideAnimationEnabled = true
            syncState()
        }

        drawerToggle?.let { drawerLayout.addDrawerListener(it) }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return

        val navController = navHostFragment.navController

        // Null-safe references — these views only exist in the phone layout
        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        )
        val navigationView = binding.root.findViewById<com.google.android.material.navigation.NavigationView>(
            R.id.navigation_view
        )

        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.sportsFragment
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

        navigationView?.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.floating_player_settings -> {
                    showFloatingPlayerDialog()
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.contactFragment, R.id.networkStreamFragment, R.id.playlistsFragment -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    drawerLayout?.postDelayed({
                        navController.navigate(menuItem.itemId)
                    }, 250)
                    true
                }
                else -> {
                    if (menuItem.itemId in topLevelDestinations) {
                        navigateTopLevel(menuItem.itemId)
                    }
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    true
                }
            }
        }

        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            if (menuItem.itemId in topLevelDestinations) {
                navigateTopLevel(menuItem.itemId)
                return@setOnItemSelectedListener true
            }
            return@setOnItemSelectedListener false
        }

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

            showRefreshIcon = when (destination.id) {
                R.id.homeFragment,
                R.id.liveEventsFragment,
                R.id.sportsFragment,
                R.id.categoryChannelsFragment,
                R.id.playlistsFragment,
                R.id.favoritesFragment -> true
                else -> false
            }
            invalidateOptionsMenu()

            val isTopLevel = destination.id in topLevelDestinations
            val isNetworkStream = destination.id == R.id.networkStreamFragment

            if (isNetworkStream) {
                binding.btnSearch.visibility = View.GONE
                binding.btnFavorites.visibility = View.GONE
            } else {
                binding.btnSearch.visibility = View.VISIBLE
                binding.btnFavorites.visibility = View.VISIBLE
            }

            if (isTopLevel) {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                animateNavigationIcon(0f)
                binding.toolbar.setNavigationOnClickListener {
                    if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout?.openDrawer(GravityCompat.START)
                    }
                }
                binding.bottomNavigation.menu.findItem(destination.id)?.isChecked = true
            } else {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                binding.toolbar.post {
                    animateNavigationIcon(1f)
                }
                binding.toolbar.setNavigationOnClickListener {
                    onBackPressedDispatcher.onBackPressed()
                }
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

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            showSearch()
        }

        binding.searchView.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { query ->
                    val navHostFragment = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    val currentFragment =
                        navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                    if (currentFragment is SearchableFragment) {
                        currentFragment.onSearchQuery(query)
                    }
                    binding.btnSearchClear.visibility =
                        if (query.isNotEmpty()) View.VISIBLE else View.GONE
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

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentId = navHostFragment?.navController?.currentDestination?.id
        val topLevelDestinations = setOf(
            R.id.homeFragment,
            R.id.liveEventsFragment,
            R.id.sportsFragment
        )
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
        val startPosition = drawerToggle?.drawerArrowDrawable?.progress ?: 0f
        if (startPosition == endPosition) return
        val animator = ValueAnimator.ofFloat(startPosition, endPosition)
        animator.addUpdateListener { valueAnimator ->
            val slideOffset = valueAnimator.animatedValue as Float
            drawerToggle?.drawerArrowDrawable?.progress = slideOffset
        }
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.duration = 300
        animator.start()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle?.syncState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        )
        when {
            drawerLayout?.isDrawerOpen(GravityCompat.START) == true ->
                drawerLayout.closeDrawer(GravityCompat.START)
            isTvDevice && isSearchVisible -> hideTvSearch()
            !isTvDevice && isSearchVisible -> hideSearch()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
