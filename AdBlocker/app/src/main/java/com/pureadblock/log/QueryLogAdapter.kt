package com.pureadblock.log

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pureadblock.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QueryLogAdapter(
    private val onDomainClick: (String) -> Unit
) : RecyclerView.Adapter<QueryLogAdapter.ViewHolder>() {

    private var entries: List<QueryLog.Entry> = emptyList()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newEntries: List<QueryLog.Entry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_query_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.textDomain.text = entry.domain
        holder.textTime.text = timeFormat.format(Date(entry.timestamp))

        // Blocked = red dot, allowed = green dot.
        holder.statusDot.setBackgroundResource(
            if (entry.blocked) R.drawable.circle_red else R.drawable.circle_green
        )

        holder.itemView.setOnClickListener { onDomainClick(entry.domain) }
    }

    override fun getItemCount(): Int = entries.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.statusDot)
        val textDomain: TextView = view.findViewById(R.id.textDomain)
        val textTime: TextView = view.findViewById(R.id.textTime)
    }
}
