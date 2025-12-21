package com.livetvpro.ui.player.settings

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
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

    // Update selection without recreating the adapter
    fun updateSelection(selectedItem: T) {
        items.forEachIndexed { index, item ->
            val wasSelected = item.isSelected
            val isSelected = item == selectedItem
            
            // Use type-safe copying
            @Suppress("UNCHECKED_CAST")
            val updatedItem = when (item) {
                is TrackUiModel.Video -> (item as TrackUiModel.Video).copy(isSelected = isSelected)
                is TrackUiModel.Audio -> (item as TrackUiModel.Audio).copy(isSelected = isSelected)
                is TrackUiModel.Text -> (item as TrackUiModel.Text).copy(isSelected = isSelected)
                is TrackUiModel.Speed -> (item as TrackUiModel.Speed).copy(isSelected = isSelected)
                else -> item
            } as T
            
            items[index] = updatedItem
            
            // Only refresh changed items
            if (wasSelected != isSelected) {
                notifyItemChanged(index)
            }
        }
    }

    inner class VH(val binding: ItemTrackOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: T) {
            // RED COLOR for radio and checkbox
            val redColor = ContextCompat.getColor(binding.root.context, R.color.accent)
            val redColorStateList = ColorStateList.valueOf(redColor)
            
            // Update radio button or checkbox based on isRadio flag
            val radioButton = binding.root.findViewById<android.widget.RadioButton>(R.id.radioButton)
            val checkBox = binding.root.findViewById<android.widget.CheckBox>(R.id.checkBox)
            
            if (item.isRadio) {
                // Show radio button, hide checkbox
                radioButton.visibility = View.VISIBLE
                checkBox.visibility = View.GONE
                radioButton.isChecked = item.isSelected
                radioButton.buttonTintList = redColorStateList
            } else {
                // Show checkbox, hide radio button
                radioButton.visibility = View.GONE
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = item.isSelected
                checkBox.buttonTintList = redColorStateList
            }

            when (item) {
                is TrackUiModel.Video -> {
                    when {
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No video"
                        }
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic quality"
                        }
                        else -> {
                            // Quality
                            val quality = if (item.width > 0 && item.height > 0) {
                                "${item.width} × ${item.height}"
                            } else {
                                "Unknown quality"
                            }
                            
                            // Bitrate
                            val bitrate = if (item.bitrate > 0) {
                                "${"%.2f".format(item.bitrate / 1_000_000f)} Mbps"
                            } else {
                                "Unknown bitrate"
                            }
                            
                            binding.tvPrimary.text = quality
                            binding.tvSecondary.text = bitrate
                        }
                    }
                }

                is TrackUiModel.Audio -> {
                    when {
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No audio"
                        }
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic audio"
                        }
                        else -> {
                            // Language
                            val language = if (item.language.isNotEmpty() && item.language != "und") {
                                item.language.uppercase()
                            } else {
                                "Unknown"
                            }
                            
                            // Channels
                            val channels = if (item.channels > 0) {
                                " • ${item.channels}ch"
                            } else {
                                ""
                            }
                            
                            // Bitrate
                            val bitrate = if (item.bitrate > 0) {
                                "${item.bitrate / 1000} kbps"
                            } else {
                                "Unknown bitrate"
                            }
                            
                            binding.tvPrimary.text = "$language$channels"
                            binding.tvSecondary.text = bitrate
                        }
                    }
                }

                is TrackUiModel.Text -> {
                    when {
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No subtitles"
                        }
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic subtitles"
                        }
                        else -> {
                            // Language
                            val language = if (item.language.isNotEmpty() && item.language != "und") {
                                item.language.uppercase()
                            } else {
                                "Unknown"
                            }
                            
                            binding.tvPrimary.text = language
                            binding.tvSecondary.text = "Subtitles"
                        }
                    }
                }

                is TrackUiModel.Speed -> {
                    // SHOW ACTUAL SPEED VALUE
                    binding.tvPrimary.text = "${item.speed}x"
                    binding.tvSecondary.text = "Playback speed"
                }
            }

            binding.root.setOnClickListener { 
                onSelect(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrackOptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position])

    override fun getItemCount() = items.size
}
