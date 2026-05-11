package com.telenav.netmonitor

interface NetworkUsageSource {
    /** Start receiving events. Idempotent — safe to call multiple times. */
    fun subscribe(listener: (List<SubsystemUsage>) -> Unit)
    /** Stop receiving events. */
    fun unsubscribe()
}

data class SubsystemUsage(
    val subsystem: String,
    val uploadBytes: Long,
    val downloadBytes: Long,
) {
    val totalBytes: Long get() = uploadBytes + downloadBytes
}
