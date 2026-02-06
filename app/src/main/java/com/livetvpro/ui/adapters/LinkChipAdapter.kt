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
import com.livetvpro.data.models.LiveEventLink

class LinkChipAdapter(
    private val onLinkClick: (LiveEventLink, Int) -> Unit
) : ListAdapter<LiveEventLink, LinkChipAdapter.LinkViewHolder>(LinkDiffCallback()) {

    private var selectedPosition = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_link_chip, parent, false)
        return LinkViewHolder(view)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(position)
    }

    inner class LinkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val linkTitle: TextView = itemView.findViewById(R.id.linkTitle)
        private val checkIcon: ImageView = itemView.findViewById(R.id.checkIcon)

        fun bind(link: LiveEventLink, isSelected: Boolean) {
            linkTitle.text = link.quality
            checkIcon.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.isSelected = isSelected
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onLinkClick(link, position)
                }
            }
        }
    }

    private class LinkDiffCallback : DiffUtil.ItemCallback<LiveEventLink>() {
        override fun areItemsTheSame(oldItem: LiveEventLink, newItem: LiveEventLink): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: LiveEventLink, newItem: LiveEventLink): Boolean {
            return oldItem == newItem
        }
    }
}
