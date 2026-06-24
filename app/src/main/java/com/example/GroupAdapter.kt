package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GroupAdapter(
    private var groups: List<String>,
    private val onGroupClick: (String, Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    private var selectedIndex = 0

    fun updateData(newGroups: List<String>) {
        this.groups = newGroups
        notifyDataSetChanged()
    }

    fun setSelection(index: Int) {
        val oldIndex = selectedIndex
        selectedIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val groupName = groups[position]
        holder.bind(groupName, position == selectedIndex)
    }

    override fun getItemCount(): Int = groups.size

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupName: TextView = itemView.findViewById(R.id.tvGroupName)
        private val tvGroupCode: TextView = itemView.findViewById(R.id.tvGroupCode)
        private val badgeLive: View = itemView.findViewById(R.id.badgeGroupLive)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onGroupClick(groups[pos], pos)
                }
            }
        }

        fun bind(groupName: String, isSelected: Boolean) {
            tvGroupName.text = groupName
            
            // Initial/Abbreviation for code singkat
            val initials = getInitials(groupName)
            tvGroupCode.text = initials

            if (isSelected) {
                itemView.setBackgroundResource(R.drawable.bg_channel_active)
                tvGroupName.setTextColor(itemView.context.getColor(R.color.accent))
                tvGroupCode.setTextColor(itemView.context.getColor(R.color.accent))
            } else {
                itemView.setBackgroundResource(android.R.color.transparent)
                tvGroupName.setTextColor(itemView.context.getColor(R.color.text_primary))
                tvGroupCode.setTextColor(itemView.context.getColor(R.color.text_secondary))
            }

            badgeLive.visibility = View.GONE
        }

        private fun getInitials(name: String): String {
            val words = name.split(" ", "-", "_").filter { it.isNotEmpty() }
            return if (words.size >= 2) {
                (words[0].take(1) + words[1].take(1)).uppercase()
            } else if (name.isNotEmpty()) {
                name.take(3).uppercase()
            } else {
                "TV"
            }
        }
    }
}
