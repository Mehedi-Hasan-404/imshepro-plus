package com.livetvpro.ui.player.settings

import android.view.LayoutInflater
import android.view.ViewGroup
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

    inner class VH(val binding: ItemTrackOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: T) {
            binding.radioButton.isChecked = item.isSelected

            when (item) {
                is TrackUiModel.Video -> {
                    binding.tvPrimary.text = "${item.width} × ${item.height}"
                    binding.tvSecondary.text =
                        "${"%.2f".format(item.bitrate / 1_000_000f)} Mbps"
                }

                is TrackUiModel.Audio -> {
                    binding.tvPrimary.text =
                        "${item.language.uppercase()} • ${item.channels}ch"
                    binding.tvSecondary.text =
                        "${item.bitrate / 1000} kbps"
                }

                is TrackUiModel.Text -> {
                    binding.tvPrimary.text = item.language
                    binding.tvSecondary.text = ""
                }
            }

            binding.root.setOnClickListener { onSelect(item) }
            binding.radioButton.setOnClickListener { onSelect(item) }
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
