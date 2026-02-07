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
                channelName.text = channel.name

                // Load channel logo
                Glide.with(itemView.context)
                    .load(channel.logoUrl)
                    .placeholder(R.drawable.ic_tv_placeholder)
                    .error(R.drawable.ic_tv_placeholder)
                    .into(channelLogo)

                // Handle item click
                root.setOnClickListener {
                    // Check if custom click listener is set
                    if (onChannelClickListener != null) {
                        // Use custom listener
                        onChannelClickListener?.invoke(channel)
                    } else {
                        // Default behavior - check floating player preference
                        if (preferencesManager.isFloatingPlayerEnabled()) {
                            FloatingPlayerHelper.playChannel(itemView.context, channel)
                        } else {
                            // Normal full-screen player
                            val intent = Intent(itemView.context, PlayerActivity::class.java).apply {
                                putExtra("channel", channel)
                            }
                            itemView.context.startActivity(intent)
                        }
                    }
                }

                // Handle long click
                root.setOnLongClickListener {
                    onChannelLongClickListener?.invoke(channel)
                    true
                }
            }
        }
    }

    fun setOnChannelClickListener(listener: (Channel) -> Unit) {
        this.onChannelClickListener = listener
    }

    fun setOnChannelLongClickListener(listener: (Channel) -> Unit) {
        this.onChannelLongClickListener = listener
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
