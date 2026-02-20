package com.livetvpro

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
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
import com.livetvpro.utils.DeviceUtils
import com.livetvpro.utils.NativeListenerManager
import com.livetvpro.utils.Refreshable
import dagger.hilt.android.AndroidEntryPoint
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

    @Inject
    lateinit var listenerManager: NativeListenerManager

    private var drawerToggle: ActionBarDrawerToggle? = null
    private var isSearchVisible = false
    private var showRefreshIcon = false

    // Phone-only views — populated in setupNavigation(), null on TV
    private var phoneToolbar: com.google.android.material.appbar.MaterialToolbar? = null
    private var phoneToolbarTitle: android.widget.TextView? = null
    private var phoneBtnSearch: android.widget.ImageButton? = null
    private var phoneBtnFavorites: android.widget.ImageButton? = null
    private var phoneSearchView: androidx.appcompat.widget.SearchView? = null
    private var phoneBtnSearchClear: android.widget.ImageButton? = null

    companion object {
        private const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
    }

    // Modern replacement for deprecated startActivityForResult
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            FloatingPlayerDialog.newInstance().show(supportFragmentManager, FloatingPlayerDialog.TAG)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themeManager.applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleNotificationIntent(intent)

        applyBergenSansToNavigationMenu()

        if (DeviceUtils.isTvDevice) {
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    R.id.drawer_layout
                )
                when {
                    drawerLayout?.isDrawerOpen(GravityCompat.START) == true ->
                        drawerLayout.closeDrawer(GravityCompat.START)
                    DeviceUtils.isTvDevice && isSearchVisible -> hideTvSearch()
                    !DeviceUtils.isTvDevice && isSearchVisible -> hideSearch()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun applyBergenSansToNavigationMenu() {
        val bergenSans = ResourcesCompat.getFont(this, R.font.bergen_sans) ?: return
        val navigationView = binding.root.findViewById<com.google.android.material.navigation.NavigationView>(
            R.id.navigation_view
        ) ?: return
        val menu = navigationView.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val spannable = SpannableString(item.title)
            spannable.setSpan(CustomTypefaceSpan(bergenSans), 0, spannable.length, 0)
            item.title = spannable
            val subMenu = item.subMenu
            if (subMenu != null) {
                for (j in 0 until subMenu.size()) {
                    val subItem = subMenu.getItem(j)
                    val subSpannable = SpannableString(subItem.title)
                    subSpannable.setSpan(CustomTypefaceSpan(bergenSans), 0, subSpannable.length, 0)
                    subItem.title = subSpannable
                }
            }
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

        if (!DeviceUtils.isTvDevice) {
            // Orientation / screen size changed — re-evaluate status bar visibility
            handleStatusBarForOrientation()

            // Grid column count may have changed (e.g. phone rotated, foldable unfolded).
            // Propagate to the active fragment so it can update its GridLayoutManager span.
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
                ?.onConfigurationChanged(newConfig)
        }
    }

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

        val tvToolbar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(
            R.id.toolbar
        )
        setSupportActionBar(tvToolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            tvToolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        ).apply {
            isDrawerIndicatorEnabled = true
            syncState()
        }
        drawerToggle?.let { drawerLayout?.addDrawerListener(it) }

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

        tabDestinations.forEach { (viewId, destId) ->
            binding.root.findViewById<android.widget.TextView>(viewId)?.setOnClickListener {
                navigate(destId)
            }
        }

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
                R.id.nav_contact_browser -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val contactUrl = listenerManager.getContactUrl().takeIf { it.isNotBlank() }
                    if (contactUrl != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contactUrl)))
                    true
                }
                R.id.nav_website -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val webUrl = listenerManager.getWebUrl().takeIf { it.isNotBlank() }
                    if (webUrl != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                    true
                }
                R.id.nav_email_us -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val email = listenerManager.getEmailUs().takeIf { it.isNotBlank() }
                    if (email != null) {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, "LiveTVPro Support")
                        }
                        startActivity(Intent.createChooser(intent, "Send Email"))
                    }
                    true
                }
                R.id.contactFragment, R.id.networkStreamFragment, R.id.playlistsFragment,
                R.id.cricketScoreFragment, R.id.footballScoreFragment -> {
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

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val activeDestId = when (destination.id) {
                R.id.categoryChannelsFragment -> R.id.homeFragment
                else -> destination.id
            }
            selectTab(activeDestId)

            val isTopLevel = destination.id in topLevelDestinations

            if (isTopLevel) {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                animateNavigationIcon(0f)
                tvToolbar?.setNavigationOnClickListener {
                    if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout?.openDrawer(GravityCompat.START)
                    }
                }
            } else {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                tvToolbar?.post {
                    animateNavigationIcon(1f)
                }
                tvToolbar?.setNavigationOnClickListener {
                    onBackPressedDispatcher.onBackPressed()
                }
            }

            if (isSearchVisible) {
                hideTvSearch()
            }
        }

        selectTab(navController.graph.startDestinationId)

        binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search)
            ?.setOnClickListener { if (isSearchVisible) hideTvSearch() else showTvSearch() }

        val tvSearchView = binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        val tvClearBtn = binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search_clear)

        tvClearBtn?.visibility = View.VISIBLE

        tvSearchView?.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                newText?.let { query ->
                    val nhf = supportFragmentManager
                        .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                    val frag = nhf?.childFragmentManager?.fragments?.firstOrNull()
                    if (frag is SearchableFragment) frag.onSearchQuery(query)
                }
                return true
            }
        })

        tvClearBtn?.setOnClickListener {
            hideTvSearch()
        }
    }

    private fun animateTvSearchWeight(from: Float, to: Float, onEnd: (() -> Unit)? = null) {
        val tvSearchBar = binding.root.findViewById<View>(R.id.tv_search_bar) ?: return
        val lp = tvSearchBar.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            interpolator = if (to > from)
                android.view.animation.DecelerateInterpolator()
            else
                android.view.animation.AccelerateInterpolator()
            addUpdateListener { va ->
                lp.weight = va.animatedValue as Float
                tvSearchBar.layoutParams = lp
            }
            onEnd?.let {
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { it() }
                })
            }
            start()
        }
    }

    private fun showTvSearch() {
        isSearchVisible = true
        val tvSearchBar = binding.root.findViewById<View>(R.id.tv_search_bar) ?: return
        val lp = tvSearchBar.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        lp.weight = 0f
        tvSearchBar.layoutParams = lp
        tvSearchBar.visibility = View.VISIBLE
        animateTvSearchWeight(0f, 2f)

        binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)?.post {
            binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
                ?.apply { isIconified = false; requestFocus() }
        }
    }

    private fun hideTvSearch() {
        isSearchVisible = false
        binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
            ?.apply { setQuery("", false); clearFocus() }
        animateTvSearchWeight(2f, 0f) {
            binding.root.findViewById<View>(R.id.tv_search_bar)?.visibility = View.GONE
        }
    }

    private fun setupToolbar() {
        val toolbar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(
            R.id.toolbar
        ) ?: return
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (DeviceUtils.isTvDevice) return false
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (DeviceUtils.isTvDevice) return false
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
        val toolbar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(
            R.id.toolbar
        ) ?: return

        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
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

        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        )
        val navigationView = binding.root.findViewById<com.google.android.material.navigation.NavigationView>(
            R.id.navigation_view
        )
        val toolbar = binding.root.findViewById<com.google.android.material.appbar.MaterialToolbar>(
            R.id.toolbar
        )
        val toolbarTitle = binding.root.findViewById<android.widget.TextView>(R.id.toolbar_title)
        val bottomNavigation = binding.root.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_navigation
        )
        val btnSearch = binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search)
        val btnFavorites = binding.root.findViewById<android.widget.ImageButton>(R.id.btn_favorites)
        val searchView = binding.root.findViewById<androidx.appcompat.widget.SearchView>(R.id.search_view)
        val btnSearchClear = binding.root.findViewById<android.widget.ImageButton>(R.id.btn_search_clear)

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
                R.id.nav_contact_browser -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val contactUrl = listenerManager.getContactUrl().takeIf { it.isNotBlank() }
                    if (contactUrl != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(contactUrl)))
                    true
                }
                R.id.nav_website -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val webUrl = listenerManager.getWebUrl().takeIf { it.isNotBlank() }
                    if (webUrl != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)))
                    true
                }
                R.id.nav_email_us -> {
                    drawerLayout?.closeDrawer(GravityCompat.START)
                    val email = listenerManager.getEmailUs().takeIf { it.isNotBlank() }
                    if (email != null) {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                            putExtra(Intent.EXTRA_SUBJECT, "LiveTVPro Support")
                        }
                        startActivity(Intent.createChooser(intent, "Send Email"))
                    }
                    true
                }
                R.id.networkStreamFragment, R.id.playlistsFragment,
                R.id.cricketScoreFragment, R.id.footballScoreFragment -> {
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

        bottomNavigation?.setOnItemSelectedListener { menuItem ->
            if (menuItem.itemId in topLevelDestinations) {
                navigateTopLevel(menuItem.itemId)
                return@setOnItemSelectedListener true
            }
            return@setOnItemSelectedListener false
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            toolbarTitle?.text = when (destination.id) {
                R.id.homeFragment -> "Categories"
                R.id.categoryChannelsFragment -> "Channels"
                R.id.liveEventsFragment -> getString(R.string.app_name)
                R.id.favoritesFragment -> "Favorites"
                R.id.sportsFragment -> "Sports"
                R.id.contactFragment -> "Contact"
                R.id.networkStreamFragment -> "Network Stream"
                R.id.playlistsFragment -> "Playlists"
                R.id.cricketScoreFragment -> "Cricket Score"
                R.id.footballScoreFragment -> "Football Score"
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
                btnSearch?.visibility = View.GONE
                btnFavorites?.visibility = View.GONE
            } else {
                btnSearch?.visibility = View.VISIBLE
                btnFavorites?.visibility = View.VISIBLE
            }

            if (isTopLevel) {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                animateNavigationIcon(0f)
                toolbar?.setNavigationOnClickListener {
                    if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        drawerLayout?.openDrawer(GravityCompat.START)
                    }
                }
                bottomNavigation?.menu?.findItem(destination.id)?.isChecked = true
            } else {
                drawerLayout?.setDrawerLockMode(
                    androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                )
                drawerToggle?.isDrawerIndicatorEnabled = true
                toolbar?.post {
                    animateNavigationIcon(1f)
                }
                toolbar?.setNavigationOnClickListener {
                    onBackPressedDispatcher.onBackPressed()
                }
            }

            if (isSearchVisible) {
                hideSearch()
            }
        }

        btnFavorites?.setOnClickListener {
            if (navController.currentDestination?.id != R.id.favoritesFragment) {
                navController.navigate(R.id.favoritesFragment)
            }
        }

        // Store phone-only views for use in showSearch/hideSearch
        phoneToolbar = toolbar
        phoneToolbarTitle = toolbarTitle
        phoneBtnSearch = btnSearch
        phoneBtnFavorites = btnFavorites
        phoneSearchView = searchView
        phoneBtnSearchClear = btnSearchClear
    }

    private fun showFloatingPlayerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Permission Required")
                .setMessage("Floating Player requires permission to draw over other apps. Please enable it in the next screen.")
                .setPositiveButton("Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            FloatingPlayerDialog.newInstance().show(supportFragmentManager, FloatingPlayerDialog.TAG)
        }
    }

    private fun setupSearch() {
        phoneBtnSearch?.setOnClickListener {
            showSearch()
        }

        phoneSearchView?.setOnQueryTextListener(object :
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
                    phoneBtnSearchClear?.visibility =
                        if (query.isNotEmpty()) View.VISIBLE else View.GONE
                }
                return true
            }
        })

        phoneBtnSearchClear?.setOnClickListener {
            phoneSearchView?.setQuery("", false)
        }
    }

    private fun showSearch() {
        isSearchVisible = true
        phoneToolbarTitle?.visibility = View.GONE
        phoneBtnSearch?.visibility = View.GONE
        phoneBtnFavorites?.visibility = View.GONE
        phoneSearchView?.visibility = View.VISIBLE
        phoneSearchView?.isIconified = false
        phoneSearchView?.requestFocus()
        animateNavigationIcon(1f)
        phoneToolbar?.setNavigationOnClickListener {
            hideSearch()
        }
    }

    private fun hideSearch() {
        isSearchVisible = false
        phoneToolbarTitle?.visibility = View.VISIBLE
        phoneBtnSearch?.visibility = View.VISIBLE
        phoneBtnFavorites?.visibility = View.VISIBLE
        phoneSearchView?.visibility = View.GONE
        phoneBtnSearchClear?.visibility = View.GONE
        phoneSearchView?.setQuery("", false)
        phoneSearchView?.clearFocus()

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

        val drawerLayout = binding.root.findViewById<androidx.drawerlayout.widget.DrawerLayout>(
            R.id.drawer_layout
        )
        if (isTopLevel) {
            phoneToolbar?.setNavigationOnClickListener {
                if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    drawerLayout?.openDrawer(GravityCompat.START)
                }
            }
        } else {
            phoneToolbar?.setNavigationOnClickListener {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val url = intent?.getStringExtra("url") ?: return
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) { }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle?.syncState()
    }

}

private class CustomTypefaceSpan(private val typeface: Typeface) : android.text.style.TypefaceSpan("") {
    override fun updateDrawState(ds: android.text.TextPaint) {
        ds.typeface = typeface
    }
    override fun updateMeasureState(paint: android.text.TextPaint) {
        paint.typeface = typeface
    }
}
