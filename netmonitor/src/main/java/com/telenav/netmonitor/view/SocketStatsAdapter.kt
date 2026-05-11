package com.telenav.netmonitor.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telenav.netmonitor.NetMonitorConfig
import com.telenav.netmonitor.R
import com.telenav.netmonitor.SocketTrafficRow

/**
 * RecyclerView rows for NetScope Layer D — one per `remoteAddress` (IP:port),
 * with the protocol label rendered as a `[HTTPS]` prefix.
 *
 * When [SocketTrafficRow.domains] is non-empty, a third (italic amber) line
 * shows the matched known-host name(s); otherwise the line is hidden.
 */
class SocketStatsAdapter : RecyclerView.Adapter<SocketStatsAdapter.VH>() {

    private var items: List<SocketTrafficRow> = emptyList()

    fun update(newItems: List<SocketTrafficRow>) {
        items = newItems.take(NetMonitorConfig.maxVisibleSockets)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_netmonitor_socket, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvAddr: TextView = view.findViewById(R.id.tv_socket_addr)
        private val tvTraffic: TextView = view.findViewById(R.id.tv_socket_traffic)
        private val tvDomains: TextView = view.findViewById(R.id.tv_socket_domains)

        fun bind(row: SocketTrafficRow) {
            tvAddr.text = if (row.protocol.isNotEmpty()) {
                "[${row.protocol}] ${row.remoteAddress}"
            } else {
                row.remoteAddress
            }
            tvTraffic.text = "↑${fmt(row.txBytes)}  ↓${fmt(row.rxBytes)}  conn=${row.connectionCount}"
            if (row.domains.isEmpty()) {
                tvDomains.visibility = View.GONE
            } else {
                tvDomains.visibility = View.VISIBLE
                tvDomains.text = formatDomains(row.domains)
                tvDomains.contentDescription = row.domains.joinToString(", ")
            }
        }

        private fun formatDomains(domains: List<String>): String {
            if (domains.size <= MAX_INLINE_DOMAINS) return domains.joinToString(", ")
            val head = domains.take(MAX_INLINE_DOMAINS).joinToString(", ")
            return "$head (+${domains.size - MAX_INLINE_DOMAINS} more)"
        }

        private companion object {
            const val MAX_INLINE_DOMAINS = 3
        }
    }
}
