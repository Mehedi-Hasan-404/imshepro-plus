package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.livetvpro.R

data class CategoryGroup(
    val name: String,
    val isSelected: Boolean = false
)

class CategoryGroupChipAdapter(
    private val onGroupSelected: (String) -> Unit
) : ListAdapter<CategoryGroup, CategoryGroupChipAdapter.GroupViewHolder>(CategoryGroupDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_group_chip, parent, false) as Chip
        return GroupViewHolder(chip, onGroupSelected)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class GroupViewHolder(
        private val chip: Chip,
        private val onGroupSelected: (String) -> Unit
    ) : RecyclerView.ViewHolder(chip) {

        fun bind(group: CategoryGroup) {
            chip.text = group.name
            chip.isChecked = group.isSelected
            
            // No icons for any chips
            chip.isChipIconVisible = false
            
            // Update chip appearance based on selection
            if (group.isSelected) {
                chip.chipBackgroundColor = ContextCompat.getColorStateList(chip.context, R.color.chip_background_selected)
                chip.setTextColor(ContextCompat.getColor(chip.context, R.color.chip_text_selected))
            } else {
                chip.chipBackgroundColor = ContextCompat.getColorStateList(chip.context, R.color.chip_background_color)
                chip.setTextColor(ContextCompat.getColor(chip.context, android.R.color.white))
            }
            
            chip.setOnClickListener {
                onGroupSelected(group.name)
            }
        }
    }

    private class CategoryGroupDiffCallback : DiffUtil.ItemCallback<CategoryGroup>() {
        override fun areItemsTheSame(oldItem: CategoryGroup, newItem: CategoryGroup): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: CategoryGroup, newItem: CategoryGroup): Boolean {
            return oldItem == newItem
        }
    }
}
