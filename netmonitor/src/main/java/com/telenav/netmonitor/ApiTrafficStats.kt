package com.telenav.netmonitor

/**
 * UI-level view model for a single API endpoint's traffic stats (Layer B).
 *
 * Maps 1:1 onto NetScope v3.0.0's [indi.arrowyi.netscope.sdk.ApiStats],
 * carrying the **per-API** ("$host$path") granularity. [apiKey] stores
 * `ApiStats.key` — the stable `"$host$path"` string such as
 * `api.telenav.com/v1/search/:id` or `:9000/telemetry` (the latter being
 * an unresolved-host bucket that still preserves the port). [host] and
 * [path] are also carried verbatim so the UI can render either the full
 * key or a `host`-grouped rollup (see NetScope README "If your HMI's
 * existing UI still groups by host …").
 *
 * Renamed from `DomainTrafficStats` in Phase 5 (v3.0.0 migration). The
 * old name no longer matches reality: v3's Layer B aggregates per
 * (host, normalised-path) pair, so a single host can surface as multiple
 * rows in [NetDataRepository.AggregatedData.layerBApis].
 *
 * The per-row numbers here are always concrete (non-nullable) because if
 * NetScope can't give us an API list at all, [NetDataRepository] emits an
 * empty list rather than a list of nullable rows. The aggregate
 * "获取不到" state is surfaced through
 * [NetDataRepository.AggregatedData.layerBTxBytes] / `layerBRxBytes` being
 * `null` — not through individual rows.
 */
data class ApiTrafficStats(
    /**
     * `ApiStats.key` = `"$host$path"` from NetScope v3. Primary display
     * string for the per-API row. When NetScope cannot parse a host,
     * this surfaces as the sentinel label injected by
     * [NetDataRepository] (see its `unknownDomainLabel` KDoc).
     */
    val apiKey: String,
    /** Raw `ApiStats.host` — may be empty / `:port` for unresolvable hosts. */
    val host: String,
    /** Raw `ApiStats.path` — always starts with `/`; query + fragment stripped. */
    val path: String,
    val txBytesTotal: Long,
    val rxBytesTotal: Long,
    val txBytesInterval: Long = 0L,
    val rxBytesInterval: Long = 0L,
    val connCountTotal: Int = 0,
    val connCountInterval: Int = 0,
    val lastActiveMs: Long = 0L,
) {
    val totalBytes: Long get() = txBytesTotal + rxBytesTotal
    val intervalBytes: Long get() = txBytesInterval + rxBytesInterval
}
