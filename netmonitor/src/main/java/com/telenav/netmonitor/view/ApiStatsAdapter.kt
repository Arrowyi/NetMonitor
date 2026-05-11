package com.telenav.netmonitor.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telenav.netmonitor.ApiTrafficStats
import com.telenav.netmonitor.NetMonitorConfig
import com.telenav.netmonitor.R

/**
 * RecyclerView adapter for Layer B per-API rows.
 *
 * Renamed from `DomainStatsAdapter` in Phase 5 (v3.0.0 migration). Each
 * row shows one [ApiTrafficStats] — i.e. one NetScope `ApiStats` whose
 * `key = "$host$path"` — so a chatty host like `api.telenav.com` may
 * contribute multiple rows.
 */
class ApiStatsAdapter : RecyclerView.Adapter<ApiStatsAdapter.ViewHolder>() {

    private var items: List<ApiTrafficStats> = emptyList()

    fun update(newItems: List<ApiTrafficStats>) {
        items = newItems.take(NetMonitorConfig.maxVisibleApis)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_netmonitor_api, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvApiKey: TextView = view.findViewById(R.id.tv_api_key)
        private val tvTraffic: TextView = view.findViewById(R.id.tv_traffic)

        fun bind(stats: ApiTrafficStats) {
            // v3 key ("$host$path") shown verbatim — operator can see both
            // which host AND which endpoint owns the bytes without us having
            // to guess which dimension matters more.
            tvApiKey.text = stats.apiKey
            // Row format: ↑tx ↓rx  conn=N   (compact, fits on one line on
            // the 1920x720 Chery dashboard even at the 2× font scale).
            // Interval values are not shown here — the floating window
            // refreshes every 2s which is the natural observation window.
            tvTraffic.text = "↑${fmt(stats.txBytesTotal)}  ↓${fmt(stats.rxBytesTotal)}  " +
                    "conn=${stats.connCountTotal}"
        }
    }
}
