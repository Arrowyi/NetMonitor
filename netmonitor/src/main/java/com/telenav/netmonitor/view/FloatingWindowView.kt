package com.telenav.netmonitor.view

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telenav.netmonitor.NetDataRepository
import com.telenav.netmonitor.R
import indi.arrowyi.netscope.sdk.Status

/**
 * Floating card: four summary rows (A / B / C / D) plus three RecyclerViews
 * (NetworkUsage subsystems, Java APIs, socket endpoints).
 *
 * No cross-layer summary rows — Layer D already covers Java HTTP at the
 * libc level (per SDK README), so summing layers would double-count.
 */
class FloatingWindowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val tvStatusChip: TextView
    private val tvClose: TextView
    private val tvLayerATotal: TextView
    private val tvLayerBTotal: TextView
    private val tvLayerBConn: TextView
    private val tvLayerCppTotal: TextView
    private val tvLayerSocketTotal: TextView
    private val tvLayerSocketConn: TextView
    private val tvLayerSocketHookBadge: TextView
    private val tvApiCount: TextView
    private val tvCppApiCount: TextView
    private val tvSocketHeading: TextView
    private val tvSocketEndpointCount: TextView
    private val tvBanner: TextView
    private val rvApis: RecyclerView
    private val rvCppApis: RecyclerView
    private val rvSockets: RecyclerView
    private val apiAdapter = ApiStatsAdapter()
    private val networkUsageAdapter = NetworkUsageAdapter()
    private val socketAdapter = SocketStatsAdapter()

    var onCloseClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        inflate(context, R.layout.layout_netmonitor_floating, this)
        tvStatusChip = findViewById(R.id.tv_status_chip)
        tvClose = findViewById(R.id.tv_close)
        tvLayerATotal = findViewById(R.id.tv_layer_a_total)
        tvLayerBTotal = findViewById(R.id.tv_layer_b_total)
        tvLayerBConn = findViewById(R.id.tv_layer_b_conn)
        tvLayerCppTotal = findViewById(R.id.tv_layer_cpp_total)
        tvLayerSocketTotal = findViewById(R.id.tv_layer_socket_total)
        tvLayerSocketConn = findViewById(R.id.tv_layer_socket_conn)
        tvLayerSocketHookBadge = findViewById(R.id.tv_layer_socket_hook_badge)
        tvApiCount = findViewById(R.id.tv_api_count)
        tvCppApiCount = findViewById(R.id.tv_cpp_api_count)
        tvSocketHeading = findViewById(R.id.tv_socket_heading)
        tvSocketEndpointCount = findViewById(R.id.tv_socket_endpoint_count)
        tvBanner = findViewById(R.id.tv_banner)
        rvApis = findViewById(R.id.rv_domains)
        rvCppApis = findViewById(R.id.rv_cpp_apis)
        rvSockets = findViewById(R.id.rv_socket_endpoints)
        rvApis.layoutManager = LinearLayoutManager(context)
        rvApis.isNestedScrollingEnabled = false
        rvApis.adapter = apiAdapter
        rvCppApis.layoutManager = LinearLayoutManager(context)
        rvCppApis.isNestedScrollingEnabled = false
        rvCppApis.adapter = networkUsageAdapter
        rvSockets.layoutManager = LinearLayoutManager(context)
        rvSockets.isNestedScrollingEnabled = false
        rvSockets.adapter = socketAdapter
        tvClose.setOnClickListener { onCloseClick?.invoke() }
    }

    fun update(data: NetDataRepository.AggregatedData) {
        bindStatusChip(data.status)

        tvLayerATotal.text = context.getString(
            R.string.netmonitor_total_traffic,
            data.layerATxBytes.fmtOrUnavailable(),
            data.layerARxBytes.fmtOrUnavailable(),
            data.layerATotalBytes.fmtOrUnavailable(),
        )

        tvLayerBTotal.text = context.getString(
            R.string.netmonitor_total_traffic,
            data.layerBTxBytes.fmtOrUnavailable(),
            data.layerBRxBytes.fmtOrUnavailable(),
            data.layerBTotalBytes.fmtOrUnavailable(),
        )
        tvLayerBConn.text = context.getString(
            R.string.netmonitor_conn_count,
            data.layerBConnCount.fmtIntOrUnavailable(),
        )

        tvLayerCppTotal.text = context.getString(
            R.string.netmonitor_total_traffic,
            fmt(data.layerCTxBytes),
            fmt(data.layerCRxBytes),
            fmt(data.layerCTotalBytes),
        )

        tvLayerSocketTotal.text = context.getString(
            R.string.netmonitor_total_traffic,
            data.layerSocketTxBytes.fmtOrUnavailable(),
            data.layerSocketRxBytes.fmtOrUnavailable(),
            data.layerSocketTotalBytes.fmtOrUnavailable(),
        )
        tvLayerSocketConn.text = context.getString(
            R.string.netmonitor_socket_conn,
            data.layerSocketConnCount.fmtIntOrUnavailable(),
        )
        tvLayerSocketHookBadge.text = context.getString(
            if (data.socketHookActive) R.string.netmonitor_socket_hook_on
            else R.string.netmonitor_socket_hook_off,
        )

        tvCppApiCount.text = context.getString(R.string.netmonitor_cpp_api_count, data.networkUsageCount)
        networkUsageAdapter.update(data.layerCUsages)

        tvApiCount.text = context.getString(R.string.netmonitor_api_count, data.apiCount)
        apiAdapter.update(data.layerBApis)

        tvSocketEndpointCount.text = context.getString(
            R.string.netmonitor_socket_endpoint_count,
            data.layerSocketEndpoints.size,
        )
        socketAdapter.update(data.layerSocketEndpoints)

        bindInformationalBanner(data)
    }

    private fun bindStatusChip(status: Status) {
        tvStatusChip.text = when (status) {
            Status.ACTIVE -> context.getString(R.string.netmonitor_status_active)
            Status.NOT_INITIALIZED -> context.getString(R.string.netmonitor_status_not_initialized)
        }
        tvStatusChip.setBackgroundColor(
            when (status) {
                Status.ACTIVE -> Color.parseColor("#4D4CAF50")
                Status.NOT_INITIALIZED -> Color.parseColor("#55FFFFFF")
            },
        )
    }

    private fun bindInformationalBanner(data: NetDataRepository.AggregatedData) {
        if (data.status == Status.NOT_INITIALIZED) {
            tvBanner.text = context.getString(R.string.netmonitor_banner_not_initialized)
            tvBanner.visibility = View.VISIBLE
        } else {
            tvBanner.visibility = View.GONE
        }
    }

    private fun Long?.fmtOrUnavailable(): String =
        this?.let { fmt(it) } ?: context.getString(R.string.netmonitor_value_unavailable)

    private fun Int?.fmtIntOrUnavailable(): String =
        this?.toString() ?: context.getString(R.string.netmonitor_value_unavailable)
}
