package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.databinding.ItemTrackOptionBinding

class TrackAdapter<T : TrackUiModel>(
    private val onSelect: (T) -> Unit
) : RecyclerView.Adapter<TrackAdapter<T>.VH>() {

    private val items = mutableListOf<T>()

    fun submit(list: List<T>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun updateSelection(selectedItem: T) {
        items.forEachIndexed { index, item ->
            val wasSelected = item.isSelected
            val isSelected = item == selectedItem

            @Suppress("UNCHECKED_CAST")
            val updatedItem = when (item) {
                is TrackUiModel.Video -> (item as TrackUiModel.Video).copy(isSelected = isSelected)
                is TrackUiModel.Audio -> (item as TrackUiModel.Audio).copy(isSelected = isSelected)
                is TrackUiModel.Text -> (item as TrackUiModel.Text).copy(isSelected = isSelected)
                is TrackUiModel.Speed -> (item as TrackUiModel.Speed).copy(isSelected = isSelected)
                else -> item
            } as T

            items[index] = updatedItem

            if (wasSelected != isSelected) {
                notifyItemChanged(index)
            }
        }
    }

    inner class VH(val binding: ItemTrackOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // State lives at VH level — persists across rebinds so M3 can animate smoothly
        private val selectedState = mutableStateOf(false)
        private val isRadioState = mutableStateOf(true)
        private var currentItem: T? = null

        init {
            binding.composeToggle.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            binding.composeToggle.setContent {
                val selected by selectedState
                val isRadio by isRadioState
                val interactionSource = remember { MutableInteractionSource() }

                // Disable M3's 48dp minimum touch target — the row itself handles touch,
                // and the ComposeView is fixed at 24dp in the layout.
                @OptIn(ExperimentalMaterial3Api::class)
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    if (isRadio) {
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
                            label = "radio_scale"
                        )
                        val activeColor by animateColorAsState(
                            targetValue = if (selected) Color(0xFFFF0000) else Color(0xFF8A8A8A),
                            animationSpec = tween(200),
                            label = "radio_color"
                        )
                        RadioButton(
                            selected = selected,
                            onClick = {
                                selectedState.value = true
                                currentItem?.let { onSelect(it) }
                            },
                            interactionSource = interactionSource,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = activeColor,
                                unselectedColor = Color(0xFF8A8A8A)
                            ),
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = if (selected) 1f + (scale - 1f) * 0.15f else 1f
                                    scaleY = if (selected) 1f + (scale - 1f) * 0.15f else 1f
                                }
                        )
                    } else {
                        val checkedColor by animateColorAsState(
                            targetValue = if (selected) Color(0xFFFF0000) else Color(0xFF8A8A8A),
                            animationSpec = tween(150),
                            label = "checkbox_color"
                        )
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { checked ->
                                selectedState.value = checked
                                currentItem?.let { onSelect(it) }
                            },
                            interactionSource = interactionSource,
                            colors = CheckboxDefaults.colors(
                                checkedColor = checkedColor,
                                uncheckedColor = Color(0xFF8A8A8A),
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        fun bind(item: T) {
            currentItem = item
            // Update stable states — triggers smooth recomposition, not a composition restart
            selectedState.value = item.isSelected
            isRadioState.value = item.isRadio

            binding.tvSecondary.visibility = View.VISIBLE

            when (item) {
                is TrackUiModel.Video -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.visibility = View.GONE
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.visibility = View.GONE
                        }
                        else -> {
                            val quality = if (item.width > 0 && item.height > 0) {
                                "${item.width} × ${item.height}"
                            } else "Unknown quality"
                            val bitrate = if (item.bitrate > 0) {
                                "${"%.2f".format(item.bitrate / 1_000_000f)} Mbps"
                            } else "Unknown bitrate"
                            binding.tvPrimary.text = "$quality • $bitrate"
                            binding.tvSecondary.visibility = View.GONE
                        }
                    }
                }

                is TrackUiModel.Audio -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No audio"
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic audio"
                        }
                        else -> {
                            val language = if (item.language.isNotEmpty() && item.language != "und")
                                item.language.uppercase() else "Unknown"
                            val channels = when (item.channels) {
                                1 -> " • Mono"
                                2 -> " • Stereo"
                                6 -> " • Surround 5.1"
                                8 -> " • Surround 7.1"
                                else -> if (item.channels > 0) " • ${item.channels}ch" else ""
                            }
                            val bitrate = if (item.bitrate > 0) "${item.bitrate / 1000} kbps"
                            else "Unknown bitrate"
                            binding.tvPrimary.text = "$language$channels"
                            binding.tvSecondary.text = bitrate
                        }
                    }
                }

                is TrackUiModel.Text -> {
                    when {
                        item.groupIndex == -2 -> {
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No subtitles"
                        }
                        item.groupIndex == -1 -> {
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic subtitles"
                        }
                        else -> {
                            val language = if (item.language.isNotEmpty() && item.language != "und")
                                item.language.uppercase() else "Unknown"
                            binding.tvPrimary.text = language
                            binding.tvSecondary.text = "Subtitles"
                        }
                    }
                }

                is TrackUiModel.Speed -> {
                    binding.tvPrimary.text = "${item.speed}x"
                    binding.tvSecondary.text = "Playback speed"
                }
            }

            // Clicks are handled by the Compose RadioButton/Checkbox directly.
            // The root row click delegates to composeToggle to avoid double-firing.
            binding.root.setOnClickListener { binding.composeToggle.performClick() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackOptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size
}
