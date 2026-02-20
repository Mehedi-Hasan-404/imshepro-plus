package com.livetvpro.ui.networkstream

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import com.livetvpro.R
import com.livetvpro.databinding.FragmentNetworkStreamBinding
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.FloatingPlayerHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NetworkStreamFragment : Fragment() {

    private var _binding: FragmentNetworkStreamBinding? = null
    private val binding get() = _binding!!
    
    @Inject
    lateinit var preferencesManager: com.livetvpro.data.local.PreferencesManager

    private val userAgentOptions = listOf(
        "Default",
        "Chrome(Android)",
        "Chrome(PC)",
        "IE(PC)",
        "Firefox(PC)",
        "iPhone",
        "Nokia",
        "Custom"
    )

    private val drmSchemeOptions = listOf(
        "clearkey",
        "widevine",
        "playready"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Wrap the inflater's context with the amber theme overlay so that
        // colorControlActivated (#FFB300) is inherited by every EditText in
        // this fragment — this is what Android reads when tinting the cursor
        // and selection handle drawables at the framework level.
        val themedInflater = inflater.cloneInContext(
            ContextThemeWrapper(requireContext(), R.style.ThemeOverlay_NetworkStream_AmberHandles)
        )
        _binding = FragmentNetworkStreamBinding.inflate(themedInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupDropdowns()
        setupCustomUserAgentVisibility()
        setupPasteIcons()
        setupPlayButton()
    }

    private fun setupPasteIcons() {
        listOf(
            binding.tilStreamUrl       to binding.etStreamUrl,
            binding.tilCookie          to binding.etCookie,
            binding.tilReferer         to binding.etReferer,
            binding.tilOrigin          to binding.etOrigin,
            binding.tilDrmLicense      to binding.etDrmLicense,
            binding.tilCustomUserAgent to binding.etCustomUserAgent
        ).forEach { (til, et) ->
            // Red selection highlight (matches app accent)
            et.highlightColor = 0x55EF4444.toInt()

            // Set initial icon based on whether field already has text
            updateEndIcon(til, et.text?.isNotEmpty() == true)

            // Swap icon as text changes
            et.doOnTextChanged { text, _, _, _ ->
                updateEndIcon(til, !text.isNullOrEmpty())
            }

            // Handle icon click: paste if empty, clear if has text
            til.setEndIconOnClickListener {
                if (et.text?.isNotEmpty() == true) {
                    et.setText("")
                } else {
                    pasteFromClipboard(et)
                }
            }
        }
    }

    private fun updateEndIcon(til: com.google.android.material.textfield.TextInputLayout, hasText: Boolean) {
        til.endIconDrawable = if (hasText) {
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_close)
        } else {
            androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_paste)
        }
    }

    private fun pasteFromClipboard(et: com.google.android.material.textfield.TextInputEditText) {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(requireContext())
                ?.toString()
                ?.takeIf { it.isNotEmpty() }

            if (text != null) {
                et.setText(text)
                et.setSelection(text.length)
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not read clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDropdowns() {
        // Setup User Agent dropdown
        val userAgentAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_dropdown_menu,
            userAgentOptions
        )
        binding.actvUserAgent.setAdapter(userAgentAdapter)
        binding.actvUserAgent.setText(userAgentOptions[0], false)

        // Setup DRM Scheme dropdown
        val drmSchemeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_dropdown_menu,
            drmSchemeOptions
        )
        binding.actvDrmScheme.setAdapter(drmSchemeAdapter)
        binding.actvDrmScheme.setText(drmSchemeOptions[0], false)
    }

    private fun setupCustomUserAgentVisibility() {
        // Show/hide custom user agent field based on selection
        binding.actvUserAgent.doOnTextChanged { text, _, _, _ ->
            if (text.toString() == "Custom") {
                binding.tilCustomUserAgent.visibility = View.VISIBLE
            } else {
                binding.tilCustomUserAgent.visibility = View.GONE
            }
        }
    }

    private fun setupPlayButton() {
        binding.fabPlay.setOnClickListener {
            val streamUrl = binding.etStreamUrl.text?.toString()?.trim()
            
            if (streamUrl.isNullOrEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.stream_url_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Collect all the data
            val cookie = binding.etCookie.text?.toString()?.trim() ?: ""
            val referer = binding.etReferer.text?.toString()?.trim() ?: ""
            val origin = binding.etOrigin.text?.toString()?.trim() ?: ""
            val drmLicense = binding.etDrmLicense.text?.toString()?.trim() ?: ""
            val userAgentSelection = binding.actvUserAgent.text?.toString() ?: "Default"
            val drmScheme = binding.actvDrmScheme.text?.toString() ?: "clearkey"
            
            // Get actual user agent value
            val userAgent = if (userAgentSelection == "Custom") {
                binding.etCustomUserAgent.text?.toString()?.trim() ?: "Default"
            } else {
                userAgentSelection
            }

            // Launch PlayerActivity with network stream data
            launchPlayer(
                streamUrl = streamUrl,
                cookie = cookie,
                referer = referer,
                origin = origin,
                drmLicense = drmLicense,
                userAgent = userAgent,
                drmScheme = drmScheme
            )
        }
    }

    private fun launchPlayer(
        streamUrl: String,
        cookie: String,
        referer: String,
        origin: String,
        drmLicense: String,
        userAgent: String,
        drmScheme: String
    ) {
        val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
        val hasPermission = FloatingPlayerHelper.hasOverlayPermission(requireContext())
        
        if (floatingEnabled) {
            if (!hasPermission) {
                Toast.makeText(
                    requireContext(),
                    "Overlay permission required for floating player. Opening normally instead.",
                    Toast.LENGTH_LONG
                ).show()
                
                launchFullscreenPlayer(streamUrl, cookie, referer, origin, drmLicense, userAgent, drmScheme)
                return
            }
            
            try {
                // Launch floating player for network stream
                FloatingPlayerHelper.launchFloatingPlayerWithNetworkStream(
                    context = requireContext(),
                    streamUrl = streamUrl,
                    cookie = cookie,
                    referer = referer,
                    origin = origin,
                    drmLicense = drmLicense,
                    userAgent = userAgent,
                    drmScheme = drmScheme,
                    streamName = "Network Stream"
                )
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to launch floating player: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                launchFullscreenPlayer(streamUrl, cookie, referer, origin, drmLicense, userAgent, drmScheme)
            }
        } else {
            launchFullscreenPlayer(streamUrl, cookie, referer, origin, drmLicense, userAgent, drmScheme)
        }
    }
    
    private fun launchFullscreenPlayer(
        streamUrl: String,
        cookie: String,
        referer: String,
        origin: String,
        drmLicense: String,
        userAgent: String,
        drmScheme: String
    ) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            // Flag to indicate this is a network stream
            putExtra("IS_NETWORK_STREAM", true)
            
            // Stream data
            putExtra("STREAM_URL", streamUrl)
            putExtra("COOKIE", cookie)
            putExtra("REFERER", referer)
            putExtra("ORIGIN", origin)
            putExtra("DRM_LICENSE", drmLicense)
            putExtra("USER_AGENT", userAgent)
            putExtra("DRM_SCHEME", drmScheme)
            
            // Set a title for the player
            putExtra("CHANNEL_NAME", "Network Stream")
        }
        
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
