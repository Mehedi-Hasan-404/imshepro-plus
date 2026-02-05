package com.livetvpro

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.local.ThemeManager
import com.livetvpro.databinding.ActivityMainBinding
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
    
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var isSearchVisible = false
    private var lastSelectedView: View? = null 
    
    private val indicator by lazy { binding.bottomNavIndicator } 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        themeManager.applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupToolbar()
        setupDrawer()
        setupNavigation()
        setupSearch()
        setupThemeToggle()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupDrawer() {
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
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return
        
        val navController = navHostFragment.navController

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
                R.id.sportsFragment -> "Sports"
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
    }

    private fun setupIndicator(destinationId: Int?) {
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
    }
    
    private fun animateBottomNavItem(newSelectedView: View?) {
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

    private fun setupThemeToggle() {
        val themeAutoButton = findViewById<View>(R.id.theme_auto_button)
        val themeLightButton = findViewById<View>(R.id.theme_light_button)
        val themeDarkButton = findViewById<View>(R.id.theme_dark_button)
        
        val themeAutoIcon = findViewById<android.widget.ImageView>(R.id.theme_auto_icon)
        val themeLightIcon = findViewById<android.widget.ImageView>(R.id.theme_light_icon)
        val themeDarkIcon = findViewById<android.widget.ImageView>(R.id.theme_dark_icon)
        val themeAutoText = findViewById<android.widget.TextView>(R.id.theme_auto_text)
        val themeLightText = findViewById<android.widget.TextView>(R.id.theme_light_text)
        val themeDarkText = findViewById<android.widget.TextView>(R.id.theme_dark_text)
        
        updateThemeToggleUI(
            themeManager.getThemeMode(),
            themeAutoIcon, themeLightIcon, themeDarkIcon,
            themeAutoText, themeLightText, themeDarkText,
            animated = false
        )
        
        themeAutoButton?.setOnClickListener {
            if (themeManager.getThemeMode() != ThemeManager.THEME_AUTO) {
                animateButtonPress(themeAutoButton)
                themeManager.setThemeMode(ThemeManager.THEME_AUTO)
                updateThemeToggleUI(
                    ThemeManager.THEME_AUTO,
                    themeAutoIcon, themeLightIcon, themeDarkIcon,
                    themeAutoText, themeLightText, themeDarkText,
                    animated = true
                )
            }
        }
        
        themeLightButton?.setOnClickListener {
            if (themeManager.getThemeMode() != ThemeManager.THEME_LIGHT) {
                animateButtonPress(themeLightButton)
                themeManager.setThemeMode(ThemeManager.THEME_LIGHT)
                updateThemeToggleUI(
                    ThemeManager.THEME_LIGHT,
                    themeAutoIcon, themeLightIcon, themeDarkIcon,
                    themeAutoText, themeLightText, themeDarkText,
                    animated = true
                )
            }
        }
        
        themeDarkButton?.setOnClickListener {
            if (themeManager.getThemeMode() != ThemeManager.THEME_DARK) {
                animateButtonPress(themeDarkButton)
                themeManager.setThemeMode(ThemeManager.THEME_DARK)
                updateThemeToggleUI(
                    ThemeManager.THEME_DARK,
                    themeAutoIcon, themeLightIcon, themeDarkIcon,
                    themeAutoText, themeLightText, themeDarkText,
                    animated = true
                )
            }
        }
    }
    
    private fun updateThemeToggleUI(
        selectedMode: Int,
        themeAutoIcon: android.widget.ImageView?,
        themeLightIcon: android.widget.ImageView?,
        themeDarkIcon: android.widget.ImageView?,
        themeAutoText: android.widget.TextView?,
        themeLightText: android.widget.TextView?,
        themeDarkText: android.widget.TextView?,
        animated: Boolean = true
    ) {
        val primaryColor = ContextCompat.getColor(this, R.color.accent)
        val normalColor = ContextCompat.getColor(this, R.color.text_secondary_dark)
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.theme_button_selected)
        val normalBg = ContextCompat.getDrawable(this, R.drawable.theme_button_background)
        
        val duration = if (animated) 200L else 0L
        
        val themeAutoButton = findViewById<View>(R.id.theme_auto_button)
        val themeLightButton = findViewById<View>(R.id.theme_light_button)
        val themeDarkButton = findViewById<View>(R.id.theme_dark_button)
        
        fun animateColorChange(
            icon: android.widget.ImageView?, 
            text: android.widget.TextView?, 
            button: View?,
            toColor: Int,
            isSelected: Boolean
        ) {
            if (animated) {
                val currentIconColor = icon?.imageTintList?.defaultColor ?: normalColor
                val currentTextColor = text?.currentTextColor ?: normalColor
                
                icon?.let {
                    ValueAnimator.ofObject(ArgbEvaluator(), currentIconColor, toColor).apply {
                        this.duration = duration
                        addUpdateListener { animator ->
                            it.setColorFilter(animator.animatedValue as Int)
                        }
                        start()
                    }
                }
                
                text?.let {
                    ValueAnimator.ofObject(ArgbEvaluator(), currentTextColor, toColor).apply {
                        this.duration = duration
                        addUpdateListener { animator ->
                            it.setTextColor(animator.animatedValue as Int)
                        }
                        start()
                    }
                }
                
                button?.let {
                    if (isSelected) {
                        it.alpha = 0.7f
                        it.background = selectedBg
                        it.animate().alpha(1f).setDuration(duration).start()
                    } else {
                        it.animate().alpha(0.7f).setDuration(duration).withEndAction {
                            it.background = normalBg
                            it.alpha = 1f
                        }.start()
                    }
                }
            } else {
                icon?.setColorFilter(toColor)
                text?.setTextColor(toColor)
                button?.background = if (isSelected) selectedBg else normalBg
            }
        }
        
        animateColorChange(themeAutoIcon, themeAutoText, themeAutoButton, normalColor, false)
        animateColorChange(themeLightIcon, themeLightText, themeLightButton, normalColor, false)
        animateColorChange(themeDarkIcon, themeDarkText, themeDarkButton, normalColor, false)
        
        when (selectedMode) {
            ThemeManager.THEME_AUTO -> {
                animateColorChange(themeAutoIcon, themeAutoText, themeAutoButton, primaryColor, true)
            }
            ThemeManager.THEME_LIGHT -> {
                animateColorChange(themeLightIcon, themeLightText, themeLightButton, primaryColor, true)
            }
            ThemeManager.THEME_DARK -> {
                animateColorChange(themeDarkIcon, themeDarkText, themeDarkButton, primaryColor, true)
            }
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(100)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
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
        
        val topLevelDestinations = setOf(R.id.homeFragment, R.id.liveEventsFragment, R.id.sportsFragment)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            isSearchVisible -> hideSearch()
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> binding.drawerLayout.closeDrawer(GravityCompat.START)
            else -> super.onBackPressed()
        }
    }
}
