package com.livetvpro.ui.adapters

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemChannelBinding
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.FloatingPlayerHelper
import javax.inject.Inject

class ChannelAdapter @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    private var onChannelClickListener: ((Channel) -> Unit)? = null
    private var onChannelLongClickListener: ((Channel) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel) {
            binding.apply {
                // Set channel name
                tvChannelName.text = channel.name
                
                // Set channel category
                tvChannelCategory.text = channel.category

                // Load channel logo
                Glide.with(itemView.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(ivChannelLogo)

                // Handle item click
                root.setOnClickListener {
                    // Check if custom click listener is set
                    if (onChannelClickListener != null) {
                        onChannelClickListener?.invoke(channel)
                    } else {
                        // Default behavior: Check if floating player is enabled
                        if (preferencesManager.isFloatingPlayerEnabled()) {
                            // Start floating player
                            FloatingPlayerHelper.startFloatingPlayer(
                                context = itemView.context,
                                streamUrl = channel.streamUrl,
                                streamTitle = channel.name,
                                preferencesManager = preferencesManager
                            )
                        } else {
                            // Open normal player activity
                            val intent = Intent(itemView.context, PlayerActivity::class.java).apply {
                                putExtra("stream_url", channel.streamUrl)
                                putExtra("stream_title", channel.name)
                                putExtra("channel_logo", channel.logoUrl)
                                putExtra("channel_id", channel.id)
                            }
                            itemView.context.startActivity(intent)
                        }
                    }
                }

                // Handle long click (e.g., for favorites)
                root.setOnLongClickListener {
                    onChannelLongClickListener?.invoke(channel)
                    true
                }
            }
        }
    }

    fun setOnChannelClickListener(listener: (Channel) -> Unit) {
        onChannelClickListener = listener
    }

    fun setOnChannelLongClickListener(listener: (Channel) -> Unit) {
        onChannelLongClickListener = listener
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}

// Channel data class (if you don't have it already)
// Put this in app/src/main/java/com/livetvpro/data/models/Channel.kt

/*
package com.livetvpro.data.models

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val category: String,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val language: String? = null,
    val country: String? = null
)
*/
