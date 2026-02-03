package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R

class CategoryGroupDialogAdapter(
    private val onGroupClick: (String) -> Unit
) : ListAdapter<String, CategoryGroupDialogAdapter.ViewHolder>(GroupDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_group_dialog, parent, false)
        return ViewHolder(view, onGroupClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        itemView: View,
        private val onGroupClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val groupName: TextView = itemView.findViewById(R.id.group_name)
        private val groupIcon: ImageView = itemView.findViewById(R.id.group_icon)

        fun bind(group: String) {
            groupName.text = group
            
            // Set different icon for "All"
            if (group == "All") {
                groupIcon.setImageResource(R.drawable.ic_home)
            } else {
                groupIcon.setImageResource(R.drawable.ic_live_indicator)
            }
            
            itemView.setOnClickListener {
                onGroupClick(group)
            }
        }
    }

    private class GroupDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
