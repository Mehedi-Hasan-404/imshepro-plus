package com.livetvpro.ui.player.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFloatingPlayerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupViews()
        loadSettings()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun setupViews() {
        // Setup dropdown for max floating windows
        val maxWindowsOptions = listOf("Disable", "TWO", "Three", "FOUR", "FIVE (MAX)")
        val adapter = ArrayAdapter(requireContext(), R.layout.item_dropdown_menu, maxWindowsOptions)
        (binding.maxFloatingWindowsDropdown as? AutoCompleteTextView)?.setAdapter(adapter)

        // Floating player switch listener
        binding.floatingPlayerSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setFloatingPlayerEnabled(isChecked)
            binding.maxFloatingWindowsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Max windows dropdown listener
        (binding.maxFloatingWindowsDropdown as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val maxWindows = when (position) {
                0 -> 0 // Disable
                1 -> 2 // TWO
                2 -> 3 // Three
                3 -> 4 // FOUR
                4 -> 5 // FIVE (MAX)
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

        // Set dropdown selection
        val selectionIndex = when (maxWindows) {
            0 -> 0 // Disable
            2 -> 1 // TWO
            3 -> 2 // Three
            4 -> 3 // FOUR
            5 -> 4 // FIVE (MAX)
            else -> 0
        }
        
        (binding.maxFloatingWindowsDropdown as? AutoCompleteTextView)?.setText(
            when (maxWindows) {
                0 -> "Disable"
                2 -> "TWO"
                3 -> "Three"
                4 -> "FOUR"
                5 -> "FIVE (MAX)"
                else -> "Disable"
            },
            false
        )
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
