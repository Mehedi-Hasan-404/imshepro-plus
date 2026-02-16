package com.livetvpro.utils

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Enhanced Reusable Retry Handler with Pull-to-Refresh Support
 * 
 * Handles:
 * - Short, user-friendly error messages (2-5 words)
 * - Red retry button styling
 * - Lifecycle-aware data loading (prevents retry screen on background resume)
 * - Pull-to-refresh support
 * - Refresh icon in toolbar support
 * - Automatic error view visibility management
 * 
 * Usage:
 * 1. In ViewModel: Extend RetryViewModel
 * 2. In Fragment: Call RetryHandler.setupWithRefresh() in onViewCreated()
 * 3. In MainActivity: Call fragment.refreshData() when refresh icon clicked
 */

/**
 * Error message converter - Converts technical errors to user-friendly messages
 */
object ErrorMessageConverter {
    
    /**
     * Converts exceptions into short, user-friendly error messages (2-5 words).
     */
    fun getShortErrorMessage(error: Throwable?): String {
        if (error == null) return "Unknown error"
        
        val message = error.message?.lowercase() ?: ""
        
        return when {
            message.contains("unable to resolve host") -> "No internet"
            message.contains("timeout") -> "Connection timeout"
            message.contains("failed to connect") -> "Connection failed"
            message.contains("network") -> "Network error"
            message.contains("socket") -> "Connection lost"
            message.contains("ssl") || message.contains("certificate") -> "Security error"
            message.contains("404") -> "Not found"
            message.contains("500") || message.contains("502") || message.contains("503") -> "Server error"
            message.contains("401") || message.contains("403") -> "Access denied"
            message.isEmpty() -> "Unknown error"
            else -> "Connection error"
        }
    }
}

/**
 * Interface for fragments that support refresh
 * Implement this in your fragments to enable toolbar refresh icon
 */
interface Refreshable {
    fun refreshData()
}

/**
 * Interface for ViewModels with retry functionality
 */
interface IRetryViewModel {
    val isLoading: LiveData<Boolean>
    val error: LiveData<String?>
    fun retry()
    fun refresh()
    fun onResume()
}

/**
 * Base ViewModel with retry functionality and lifecycle awareness.
 * 
 * Prevents retry screen from showing when:
 * - Returning from background after data was already loaded
 * - Data temporarily becomes empty but was previously loaded
 * 
 * Supports:
 * - Manual retry via retry button
 * - Pull-to-refresh
 * - Toolbar refresh icon
 */
abstract class RetryViewModel : ViewModel(), IRetryViewModel {
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    override val isLoading: LiveData<Boolean> = _isLoading
    
    private val _error = MutableLiveData<String?>(null)
    override val error: LiveData<String?> = _error
    
    // Tracks if data has been successfully loaded at least once
    private var hasLoadedOnce = false
    
    /**
     * Call this when starting to load data
     */
    protected fun startLoading() {
        _isLoading.value = true
        _error.value = null
    }
    
    /**
     * Call this when data loading is complete
     * 
     * @param dataIsEmpty Whether the loaded data is empty
     * @param error The error that occurred (if any)
     */
    protected fun finishLoading(dataIsEmpty: Boolean, error: Throwable? = null) {
        _isLoading.value = false
        
        if (error != null) {
            // Only show error if we haven't loaded data before
            if (!hasLoadedOnce) {
                _error.value = ErrorMessageConverter.getShortErrorMessage(error)
            }
        } else {
            // Data loaded successfully
            if (!dataIsEmpty) {
                hasLoadedOnce = true
                _error.value = null
            } else {
                // Data is empty
                // Only show error if we haven't loaded before
                if (!hasLoadedOnce) {
                    _error.value = "No data"
                }
            }
        }
    }
    
    /**
     * Call this when user manually retries
     * Resets the loaded state to allow error display
     */
    protected fun resetForRetry() {
        hasLoadedOnce = false
        _error.value = null
    }
    
    /**
     * Call this when fragment resumes
     * Returns true if data should be reloaded
     */
    protected fun shouldReloadOnResume(): Boolean {
        return hasLoadedOnce
    }
    
    /**
     * Call this to manually set an error message
     */
    protected fun setError(message: String) {
        _error.value = message
    }
    
    /**
     * Call this to clear error
     */
    protected fun clearError() {
        _error.value = null
    }
    
    /**
     * Check if data has been loaded at least once
     */
    protected fun hasLoadedData(): Boolean {
        return hasLoadedOnce
    }
    
    /**
     * Abstract method - implement your data loading logic here
     */
    abstract fun loadData()
    
    /**
     * Retry method - resets state and reloads data
     */
    override fun retry() {
        resetForRetry()
        loadData()
    }
    
    /**
     * Refresh method - reloads data without resetting state
     * Used for pull-to-refresh and refresh icon
     */
    override fun refresh() {
        loadData()
    }
    
    /**
     * Call from Fragment's onResume()
     */
    override fun onResume() {
        if (shouldReloadOnResume()) {
            loadData()
        }
    }
}

/**
 * AndroidViewModel version of RetryViewModel for ViewModels that need Application context
 */
abstract class AndroidRetryViewModel(application: Application) : AndroidViewModel(application), IRetryViewModel {
    
    private val _isLoading = MutableLiveData<Boolean>(false)
    override val isLoading: LiveData<Boolean> = _isLoading
    
    private val _error = MutableLiveData<String?>(null)
    override val error: LiveData<String?> = _error
    
    // Tracks if data has been successfully loaded at least once
    private var hasLoadedOnce = false
    
    /**
     * Call this when starting to load data
     */
    protected fun startLoading() {
        _isLoading.value = true
        _error.value = null
    }
    
    /**
     * Call this when data loading is complete
     * 
     * @param dataIsEmpty Whether the loaded data is empty
     * @param error The error that occurred (if any)
     */
    protected fun finishLoading(dataIsEmpty: Boolean, error: Throwable? = null) {
        _isLoading.value = false
        
        if (error != null) {
            // Only show error if we haven't loaded data before
            if (!hasLoadedOnce) {
                _error.value = ErrorMessageConverter.getShortErrorMessage(error)
            }
        } else {
            // Data loaded successfully
            if (!dataIsEmpty) {
                hasLoadedOnce = true
                _error.value = null
            } else {
                // Data is empty
                // Only show error if we haven't loaded before
                if (!hasLoadedOnce) {
                    _error.value = "No data"
                }
            }
        }
    }
    
    /**
     * Call this when user manually retries
     * Resets the loaded state to allow error display
     */
    protected fun resetForRetry() {
        hasLoadedOnce = false
        _error.value = null
    }
    
    /**
     * Call this when fragment resumes
     * Returns true if data should be reloaded
     */
    protected fun shouldReloadOnResume(): Boolean {
        return hasLoadedOnce
    }
    
    /**
     * Call this to manually set an error message
     */
    protected fun setError(message: String) {
        _error.value = message
    }
    
    /**
     * Call this to clear error
     */
    protected fun clearError() {
        _error.value = null
    }
    
    /**
     * Check if data has been loaded at least once
     */
    protected fun hasLoadedData(): Boolean {
        return hasLoadedOnce
    }
    
    /**
     * Abstract method - implement your data loading logic here
     */
    abstract fun loadData()
    
    /**
     * Retry method - resets state and reloads data
     */
    override fun retry() {
        resetForRetry()
        loadData()
    }
    
    /**
     * Refresh method - reloads data without resetting state
     * Used for pull-to-refresh and refresh icon
     */
    override fun refresh() {
        loadData()
    }
    
    /**
     * Call from Fragment's onResume()
     */
    override fun onResume() {
        if (shouldReloadOnResume()) {
            loadData()
        }
    }
}

/**
 * Enhanced Retry Handler with Pull-to-Refresh Support
 */
object RetryHandler {
    
    /**
     * Setup retry handling WITH pull-to-refresh support
     * 
     * @param lifecycleOwner The fragment's lifecycle owner
     * @param viewModel The ViewModel implementing IRetryViewModel
     * @param swipeRefresh The SwipeRefreshLayout (optional, pass null if not available)
     * @param errorView The error view container
     * @param errorText The TextView showing error message
     * @param retryButton The retry button
     * @param contentView The main content view
     * @param progressBar The loading progress bar
     * @param emptyView The empty state view (optional)
     */
    fun setupWithRefresh(
        lifecycleOwner: LifecycleOwner,
        viewModel: IRetryViewModel,
        swipeRefresh: SwipeRefreshLayout?,
        errorView: View,
        errorText: TextView,
        retryButton: View,
        contentView: View,
        progressBar: View,
        emptyView: View? = null
    ) {
        // Setup retry button click
        retryButton.setOnClickListener {
            viewModel.retry()
        }
        
        // Setup pull-to-refresh
        swipeRefresh?.setOnRefreshListener {
            viewModel.refresh()
        }
        
        // Observe loading state
        viewModel.isLoading.observe(lifecycleOwner) { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            swipeRefresh?.isRefreshing = isLoading
        }
        
        // Observe error state
        viewModel.error.observe(lifecycleOwner) { error ->
            if (error != null) {
                // Show error view
                errorView.visibility = View.VISIBLE
                errorText.text = error
                retryButton.visibility = View.VISIBLE
                contentView.visibility = View.GONE
                emptyView?.visibility = View.GONE
                swipeRefresh?.isEnabled = false  // Disable pull-to-refresh when showing error
            } else {
                // Hide error view
                errorView.visibility = View.GONE
                retryButton.visibility = View.GONE
                contentView.visibility = View.VISIBLE
                swipeRefresh?.isEnabled = true  // Enable pull-to-refresh when showing content
            }
        }
    }
    
    /**
     * Setup retry handling WITHOUT pull-to-refresh
     * Use this for fragments that don't have SwipeRefreshLayout
     */
    fun setup(
        lifecycleOwner: LifecycleOwner,
        viewModel: IRetryViewModel,
        errorView: View,
        errorText: TextView,
        retryButton: View,
        contentView: View,
        progressBar: View,
        emptyView: View? = null
    ) {
        setupWithRefresh(
            lifecycleOwner = lifecycleOwner,
            viewModel = viewModel,
            swipeRefresh = null,
            errorView = errorView,
            errorText = errorText,
            retryButton = retryButton,
            contentView = contentView,
            progressBar = progressBar,
            emptyView = emptyView
        )
    }
}
