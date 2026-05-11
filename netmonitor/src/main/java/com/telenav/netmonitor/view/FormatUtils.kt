package com.telenav.netmonitor.view

internal fun fmt(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
