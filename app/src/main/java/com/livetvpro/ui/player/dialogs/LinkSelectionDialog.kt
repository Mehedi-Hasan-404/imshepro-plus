package com.livetvpro.ui.player.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.view.Window
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.livetvpro.R
import com.livetvpro.data.models.LiveEventLink


class LinkSelectionDialog(
    context: Context,
    private val links: List<LiveEventLink>,
    private val currentLink: String?,
    private val onLinkSelected: (LiveEventLink) -> Unit
) : Dialog(context, R.style.Theme_LiveTVPro) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_link_selection)
        
        // Make dialog background transparent to show card with rounded corners
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        val displayMetrics = context.resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.90).toInt()
        
        window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        
        setupViews()
    }
    
    private fun setupViews() {
        val recyclerView = findViewById<RecyclerView>(R.id.linksRecyclerView)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancel)
        
        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = LinkAdapter(links, currentLink) { selectedLink ->
            onLinkSelected(selectedLink)
            dismiss()
        }
        recyclerView.adapter = adapter
        
        btnCancel.setOnClickListener { dismiss() }
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
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_link_option, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val link = links[position]
            holder.title.text = link.label
            
            // Ensure text is always visible with high contrast
            holder.title.setTextColor(Color.WHITE)
            
            // Show tick icon if this is the current link
            val isCurrentLink = currentLink == link.url
            holder.indicator.visibility = if (isCurrentLink) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
            // Add ripple effect on click
            holder.view.setOnClickListener {
                onLinkClick(link)
            }
        }
        
        override fun getItemCount() = links.size
    }
}
