package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.Sport
import com.livetvpro.databinding.ItemSportBinding
import com.livetvpro.utils.GlideExtensions

class SportAdapter(
    private val onSportClick: (Sport) -> Unit
) : ListAdapter<Sport, SportAdapter.SportViewHolder>(SportDiffCallback()) {

    inner class SportViewHolder(val binding: ItemSportBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(sport: Sport) {
            binding.sportName.text = sport.name
            
            // Load sport logo
            GlideExtensions.loadImage(
                binding.sportLogo,
                sport.logoUrl,
                R.drawable.ic_channel_placeholder,
                R.drawable.ic_channel_placeholder,
                isCircular = true
            )
            
            // Show number of quality options available
            val qualityCount = sport.links.size
            if (qualityCount > 1) {
                binding.qualityBadge.text = "$qualityCount qualities"
            } else {
                binding.qualityBadge.text = ""
            }
            
            // Click listener
            binding.root.setOnClickListener {
                onSportClick(sport)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SportViewHolder {
        val binding = ItemSportBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SportViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SportViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private class SportDiffCallback : DiffUtil.ItemCallback<Sport>() {
        override fun areItemsTheSame(oldItem: Sport, newItem: Sport): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Sport, newItem: Sport): Boolean {
            return oldItem == newItem
        }
    }
}
