package com.telenav.netmonitor

/**
 * One row in the floating window for NetScope Layer D ([indi.arrowyi.netscope.hook.SocketStats]).
 *
 * Per-`IP:port` socket traffic. As of NetScope v3.2.8 the snapshot includes
 * **closed connections plus in-flight (still-open) fds**, merged by remote
 * address (the v3.2.6 "closed-only" limitation is gone). Each row also carries
 * a port-inferred [protocol] label — `HTTP`, `HTTPS`, `DNS`, `mDNS`, `MQTT`,
 * `QUIC`, `RTSP`, `NTP`, `DHCP`; unknown ports fall back to `TCP`/`UDP`.
 * The label is informational only and never gates byte accounting.
 *
 * [domains] is filled in by [NetDataRepository] from [KnownHostsResolver] when
 * the row's IP appears in the OEM-provided known-hosts table. Empty when there
 * is no resolver, when DNS pre-resolution has not yet completed, or when the IP
 * does not match any pre-resolved host. The UI hides the domain row in that
 * case so behavior matches the pre-feature baseline.
 */
data class SocketTrafficRow(
    val remoteAddress: String,
    val protocol: String,
    val txBytes: Long,
    val rxBytes: Long,
    val connectionCount: Int,
    val domains: List<String> = emptyList(),
) {
    val totalBytes: Long get() = txBytes + rxBytes
}
