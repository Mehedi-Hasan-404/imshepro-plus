package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.local.PreferencesManager
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.databinding.ItemFavoriteBinding
import com.livetvpro.ui.player.PlayerActivity
import com.livetvpro.utils.FloatingPlayerHelper

class FavoriteAdapter(
    private val preferencesManager: PreferencesManager,
    private val onFavoriteToggle: (FavoriteChannel) -> Unit,
    private val getLiveChannel: ((String) -> Channel?)? = null
) : ListAdapter<FavoriteChannel, FavoriteAdapter.FavoriteViewHolder>(FavoriteDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteViewHolder {
        val binding = ItemFavoriteBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FavoriteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FavoriteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FavoriteViewHolder(
        private val binding: ItemFavoriteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(favorite: FavoriteChannel) {
            binding.apply {
                tvName.text = favorite.name
                
                Glide.with(imgLogo.context)
                    .load(favorite.logoUrl)
                    .placeholder(R.drawable.ic_channel_placeholder)
                    .error(R.drawable.ic_channel_placeholder)
                    .into(imgLogo)

                root.setOnClickListener {
                    val liveChannel = getLiveChannel?.invoke(favorite.id)
                    
                    val channelToUse = if (liveChannel != null) {
                        liveChannel
                    } else {
                        Channel(
                            id = favorite.id,
                            name = favorite.name,
                            logoUrl = favorite.logoUrl,
                            streamUrl = favorite.streamUrl,
                            categoryId = favorite.categoryId,
                            categoryName = favorite.categoryName,
                            links = favorite.links
                        )
                    }
                    
                    val links = channelToUse.links
                    
                    if (links.isNullOrEmpty()) {
                        if (channelToUse.streamUrl.isNotEmpty()) {
                            launchPlayerWithUrl(channelToUse, channelToUse.streamUrl)
                        } else {
                            android.widget.Toast.makeText(
                                binding.root.context,
                                "No stream available for ${favorite.name}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        return@setOnClickListener
                    }
                    
                    if (links.size > 1) {
                        showLinkSelectionDialog(channelToUse, links)
                    } else {
                        launchPlayer(channelToUse, 0)
                    }
                }

                removeButton.setOnClickListener {
                    onFavoriteToggle(favorite)
                }
            }
        }

        private fun showLinkSelectionDialog(
            channel: Channel, 
            links: List<com.livetvpro.data.models.ChannelLink>
        ) {
            val context = binding.root.context
            val linkLabels = links.map { it.quality }.toTypedArray()
            
            MaterialAlertDialogBuilder(context)
                .setTitle("Multiple Links Available")
                .setItems(linkLabels) { dialog, which ->
                    launchPlayer(channel, which)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        private fun launchPlayer(channel: Channel, linkIndex: Int) {
            val context = binding.root.context

            val selectedLink = channel.links?.getOrNull(linkIndex)
            val streamUrl = if (selectedLink != null) {
                buildStreamUrlFromLink(selectedLink)
            } else {
                channel.streamUrl
            }

            val channelWithUrl = Channel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                streamUrl = streamUrl,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName,
                links = channel.links
            )

            val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
            val hasPermission = FloatingPlayerHelper.hasOverlayPermission(context)

            if (floatingEnabled) {
                if (!hasPermission) {
                    android.widget.Toast.makeText(
                        context,
                        "Overlay permission required for floating player. Opening normally instead.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    PlayerActivity.startWithChannel(context, channelWithUrl, linkIndex)
                    return
                }
                try {
                    FloatingPlayerHelper.launchFloatingPlayer(context, channelWithUrl, linkIndex)
                } catch (e: Exception) {
                    PlayerActivity.startWithChannel(context, channelWithUrl, linkIndex)
                }
            } else {
                PlayerActivity.startWithChannel(context, channelWithUrl, linkIndex)
            }
        }
        
        private fun buildStreamUrlFromLink(link: com.livetvpro.data.models.ChannelLink): String {
            val parts = mutableListOf<String>()
            parts.add(link.url)
            
            link.referer?.let { if (it.isNotEmpty()) parts.add("referer=$it") }
            link.cookie?.let { if (it.isNotEmpty()) parts.add("cookie=$it") }
            link.origin?.let { if (it.isNotEmpty()) parts.add("origin=$it") }
            link.userAgent?.let { if (it.isNotEmpty()) parts.add("User-Agent=$it") }
            link.drmScheme?.let { if (it.isNotEmpty()) parts.add("drmScheme=$it") }
            link.drmLicenseUrl?.let { if (it.isNotEmpty()) parts.add("drmLicense=$it") }
            
            return if (parts.size > 1) {
                parts.joinToString("|")
            } else {
                parts[0]
            }
        }
        
        private fun launchPlayerWithUrl(channel: Channel, url: String) {
            val context = binding.root.context

            val floatingEnabled = preferencesManager.isFloatingPlayerEnabled()
            val hasPermission = FloatingPlayerHelper.hasOverlayPermission(context)

            if (floatingEnabled) {
                if (!hasPermission) {
                    android.widget.Toast.makeText(
                        context,
                        "Overlay permission required for floating player. Opening normally instead.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    PlayerActivity.startWithChannel(context, channel, -1)
                    return
                }
                try {
                    FloatingPlayerHelper.launchFloatingPlayer(context, channel, -1)
                } catch (e: Exception) {
                    PlayerActivity.startWithChannel(context, channel, -1)
                }
            } else {
                PlayerActivity.startWithChannel(context, channel, -1)
            }
        }
    }

    private class FavoriteDiffCallback : DiffUtil.ItemCallback<FavoriteChannel>() {
        override fun areItemsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FavoriteChannel, newItem: FavoriteChannel): Boolean {
            return oldItem == newItem
        }
    }
}
