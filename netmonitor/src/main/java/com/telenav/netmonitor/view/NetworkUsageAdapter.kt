package com.telenav.netmonitor.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telenav.netmonitor.NetMonitorConfig
import com.telenav.netmonitor.R
import com.telenav.netmonitor.SubsystemUsage

/** Layer C — one row per DataCollector [SubsystemUsage]. */
class NetworkUsageAdapter : RecyclerView.Adapter<NetworkUsageAdapter.VH>() {

    private var items: List<SubsystemUsage> = emptyList()

    fun update(newItems: List<SubsystemUsage>) {
        items = newItems.take(NetMonitorConfig.maxVisibleNetworkUsage)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_netmonitor_api, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvApiKey: TextView = view.findViewById(R.id.tv_api_key)
        private val tvTraffic: TextView = view.findViewById(R.id.tv_traffic)

        fun bind(usage: SubsystemUsage) {
            tvApiKey.text = usage.subsystem
            tvTraffic.text = "↑${fmt(usage.uploadBytes)}  ↓${fmt(usage.downloadBytes)}"
        }
    }
}
