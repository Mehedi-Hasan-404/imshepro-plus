package com.livetvpro.ui.player.dialogs

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.DialogFragment
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.databinding.DialogFloatingPlayerSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingPlayerDialog : DialogFragment() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    private var _binding: DialogFloatingPlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFloatingPlayerSettingsBinding.inflate(LayoutInflater.from(requireContext()))
        
        setupViews()
        loadSettings()
        
        // Create standard Android AlertDialog
        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
    }

    private fun setupViews() {
        // FIX Bug 3: "Multi Floating Window" - Disable means 1 window (single), not 0 (broken)
        val maxWindowsOptions = listOf("Disable (1 window)", "2 windows", "3 windows", "4 windows", "5 windows")
        val adapter = ArrayAdapter(
            requireContext(), 
            android.R.layout.simple_dropdown_item_1line, 
            maxWindowsOptions
        )
        binding.maxFloatingWindowsDropdown.setAdapter(adapter)

        // Floating player switch listener
        binding.floatingPlayerSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setFloatingPlayerEnabled(isChecked)
            binding.maxFloatingWindowsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Multi window dropdown listener
        binding.maxFloatingWindowsDropdown.setOnItemClickListener { _, _, position, _ ->
            val maxWindows = when (position) {
                0 -> 1  // Disable = single window only
                1 -> 2
                2 -> 3
                3 -> 4
                4 -> 5
                else -> 1
            }
            preferencesManager.setMaxFloatingWindows(maxWindows)
        }

        // Close button
        binding.btnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun loadSettings() {
        val isEnabled = preferencesManager.isFloatingPlayerEnabled()
        val maxWindows = preferencesManager.getMaxFloatingWindows()

        binding.floatingPlayerSwitch.isChecked = isEnabled
        binding.maxFloatingWindowsContainer.visibility = if (isEnabled) View.VISIBLE else View.GONE

        // FIX Bug 3: map saved value back to correct dropdown label
        val selectedText = when (maxWindows) {
            0, 1 -> "Disable (1 window)"
            2 -> "2 windows"
            3 -> "3 windows"
            4 -> "4 windows"
            5 -> "5 windows"
            else -> "Disable (1 window)"
        }
        binding.maxFloatingWindowsDropdown.setText(selectedText, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "FloatingPlayerDialog"
        
        fun newInstance() = FloatingPlayerDialog()
    }
}
