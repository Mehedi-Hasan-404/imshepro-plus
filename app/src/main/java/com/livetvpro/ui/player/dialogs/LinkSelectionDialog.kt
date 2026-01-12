package com.livetvpro.ui.player.dialogs

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.LiveEventLink

class LinkSelectionDialog(
    private val context: Context,
    private val links: List<LiveEventLink>,
    private val currentLink: String?,
    private val onLinkSelected: (LiveEventLink) -> Unit
) {
    
    fun show() {
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_link_selection, null)
        
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.linksRecyclerView)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .create()
        
        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = LinkAdapter(links, currentLink) { selectedLink ->
            onLinkSelected(selectedLink)
            dialog.dismiss()
        }
        recyclerView.adapter = adapter
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }
    
    private class LinkAdapter(
        private val links: List<LiveEventLink>,
        private val currentLink: String?,
        private val onLinkClick: (LiveEventLink) -> Unit
    ) : RecyclerView.Adapter<LinkAdapter.ViewHolder>() {
        
        inner class ViewHolder(val view: android.view.View) : RecyclerView.ViewHolder(view) {
            val title: android.widget.TextView = view.findViewById(R.id.linkTitle)
            val indicator: android.widget.ImageView = view.findViewById(R.id.currentIndicator)
        }
        
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_option, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val link = links[position]
            holder.title.text = link.label
            
            val isCurrentLink = currentLink == link.url
            holder.indicator.visibility = if (isCurrentLink) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
            holder.view.setOnClickListener {
                onLinkClick(link)
            }
        }
        
        override fun getItemCount() = links.size
    }
}
