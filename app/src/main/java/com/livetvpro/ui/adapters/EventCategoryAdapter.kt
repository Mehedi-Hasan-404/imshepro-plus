package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.EventCategory
import com.livetvpro.databinding.ItemEventCategoryBinding
import com.livetvpro.utils.GlideExtensions

class EventCategoryAdapter(
    private val onCategoryClick: (EventCategory) -> Unit
) : ListAdapter<EventCategory, EventCategoryAdapter.ViewHolder>(CategoryDiffCallback()) {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEventCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    inner class ViewHolder(
        private val binding: ItemEventCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val oldPosition = selectedPosition
                    selectedPosition = position
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(position)
                    onCategoryClick(getItem(position))
                }
            }
        }

        fun bind(category: EventCategory, isSelected: Boolean) {
            binding.categoryName.text = category.name
            binding.categoryName.isSelected = true // Enable marquee scrolling
            
            // Set selection state with thicker border
            binding.root.isSelected = isSelected
            binding.categoryCard.strokeWidth = if (isSelected) 5 else 4
            binding.categoryCard.strokeColor = if (isSelected) {
                android.graphics.Color.parseColor("#EF4444") // Bright RED for selected
            } else {
                android.graphics.Color.parseColor("#5A5A5A") // Lighter Gray for unselected
            }
            
            // ✅ FIXED: Use GlideExtensions for automatic SVG support with circular crop
            GlideExtensions.loadImage(
                binding.categoryLogo,
                category.logoUrl,
                R.drawable.ic_channel_placeholder,
                R.drawable.ic_channel_placeholder,
                isCircular = true
            )
        }
    }

    private class CategoryDiffCallback : DiffUtil.ItemCallback<EventCategory>() {
        override fun areItemsTheSame(oldItem: EventCategory, newItem: EventCategory): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: EventCategory, newItem: EventCategory): Boolean {
            return oldItem == newItem
        }
    }
}
