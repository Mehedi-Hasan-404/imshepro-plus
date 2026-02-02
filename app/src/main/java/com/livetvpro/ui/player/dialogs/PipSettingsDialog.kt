package com.livetvpro.ui.player.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager

/**
 * PipSettingsDialog - Dialog for configuring Picture-in-Picture actions
 * 
 * Allows user to choose between:
 * - Skip Forward/Backward mode (10s seek)
 * - Next/Previous mode (track navigation)
 */
class PipSettingsDialog(
    context: Context,
    private val preferencesManager: PreferencesManager,
    private val onSettingsChanged: () -> Unit = {}
) : Dialog(context) {
    
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioSkipMode: RadioButton
    private lateinit var radioNextPrevMode: RadioButton
    private lateinit var btnApply: AppCompatButton
    private lateinit var btnCancel: AppCompatButton
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_pip_settings)
        
        // Make dialog width 85% of screen
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        initializeViews()
        setupListeners()
        loadCurrentSettings()
    }
    
    private fun initializeViews() {
        radioGroup = findViewById(R.id.pipActionModeGroup)
        radioSkipMode = findViewById(R.id.radioSkipMode)
        radioNextPrevMode = findViewById(R.id.radioNextPrevMode)
        btnApply = findViewById(R.id.btnApply)
        btnCancel = findViewById(R.id.btnCancel)
    }
    
    private fun setupListeners() {
        btnApply.setOnClickListener {
            saveSettings()
            onSettingsChanged()
            dismiss()
        }
        
        btnCancel.setOnClickListener {
            dismiss()
        }
    }
    
    private fun loadCurrentSettings() {
        val currentMode = preferencesManager.getPipActionMode()
        
        when (currentMode) {
            PreferencesManager.PIP_ACTION_MODE_SKIP -> {
                radioSkipMode.isChecked = true
            }
            PreferencesManager.PIP_ACTION_MODE_NEXT_PREV -> {
                radioNextPrevMode.isChecked = true
            }
        }
    }
    
    private fun saveSettings() {
        val selectedMode = when (radioGroup.checkedRadioButtonId) {
            R.id.radioSkipMode -> PreferencesManager.PIP_ACTION_MODE_SKIP
            R.id.radioNextPrevMode -> PreferencesManager.PIP_ACTION_MODE_NEXT_PREV
            else -> PreferencesManager.PIP_ACTION_MODE_SKIP
        }
        
        preferencesManager.setPipActionMode(selectedMode)
    }
}
