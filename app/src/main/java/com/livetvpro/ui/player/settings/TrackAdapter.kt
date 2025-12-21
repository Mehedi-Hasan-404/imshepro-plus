package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
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
            // Update radio icon
            val radioIcon = binding.root.findViewById<ImageView>(R.id.radioIcon)
            
            if (item.isSelected) {
                radioIcon.setImageResource(R.drawable.radio_checked)
            } else {
                radioIcon.setImageResource(R.drawable.radio_unchecked)
            }

            when (item) {
                is TrackUiModel.Video -> {
                    when {
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic quality"
                        }
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No video"
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
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic audio"
                        }
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No audio"
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
                        item.groupIndex == -1 -> {
                            // Auto option
                            binding.tvPrimary.text = "Auto"
                            binding.tvSecondary.text = "Automatic subtitles"
                        }
                        item.groupIndex == -2 -> {
                            // None option
                            binding.tvPrimary.text = "None"
                            binding.tvSecondary.text = "No subtitles"
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
