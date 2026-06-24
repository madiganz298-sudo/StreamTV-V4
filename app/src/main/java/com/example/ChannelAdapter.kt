package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

class ChannelAdapter(
    private var channels: List<Channel>,
    private val onChannelClick: (Channel, Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private var selectedIndex = -1

    fun updateData(newChannels: List<Channel>) {
        this.channels = newChannels
        notifyDataSetChanged()
    }

    fun setSelection(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }

    fun getSelectedIndex(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.bind(channel, position == selectedIndex)
    }

    override fun getItemCount(): Int = channels.size

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivLogo: ImageView = itemView.findViewById(R.id.ivChannelLogo)
        private val tvName: TextView = itemView.findViewById(R.id.tvChannelName)
        private val badgeEvent: View = itemView.findViewById(R.id.badgeEvent)
        private val badgeLiveNow: View = itemView.findViewById(R.id.badgeLiveNow)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(channels[pos], pos)
                }
            }
        }

        fun bind(channel: Channel, isSelected: Boolean) {
            tvName.text = channel.name
            
            // Highlight selected channel item
            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_channel_active)
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
            }

            // Load logo with Glide
            Glide.with(itemView.context)
                .load(channel.logoUrl)
                .placeholder(R.drawable.logo_m4di_ucih4_tv)
                .error(R.drawable.logo_m4di_ucih4_tv)
                .apply(RequestOptions.circleCropTransform())
                .into(ivLogo)

            // Badges are hidden by default per user spec (visibility=GONE) but kept in layout
            badgeEvent.visibility = View.GONE
            badgeLiveNow.visibility = View.GONE
        }
    }
}
