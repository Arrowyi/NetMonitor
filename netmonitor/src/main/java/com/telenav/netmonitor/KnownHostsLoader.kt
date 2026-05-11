package com.telenav.netmonitor

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Reads `assets/known_hosts.txt` and produces a sanitized, de-duplicated list
 * of domain names (max [MAX_HOSTS]). Comments (`#`) and blanks are skipped;
 * invalid shapes are logged and dropped.
 *
 * Any IO/parse exception is swallowed and surfaced as an empty list. The
 * caller treats empty as "feature disabled" and the UI degrades gracefully.
 */
internal object KnownHostsLoader {

    private const val ASSET_NAME = "known_hosts.txt"
    const val MAX_HOSTS: Int = 256

    private val DOMAIN_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$")

    /** Production entry — reads `assets/known_hosts.txt` from the given context. */
    fun load(context: Context): List<String> = loadFromStream {
        context.assets.open(ASSET_NAME)
    }

    /** Test seam — accepts any InputStream supplier. */
    fun loadFromStream(streamSupplier: () -> InputStream): List<String> {
        val stream = try {
            streamSupplier()
        } catch (t: Throwable) {
            NMLog.i(NMSub.DNS, "$ASSET_NAME not loadable (${t.javaClass.simpleName}: ${t.message}) — DNS reverse-lookup disabled")
            return emptyList()
        }
        return try {
            parse(stream)
        } catch (t: Throwable) {
            NMLog.w(NMSub.DNS, "$ASSET_NAME parse failed; treating as empty", t)
            emptyList()
        } finally {
            try { stream.close() } catch (_: Throwable) {}
        }
    }

    private fun parse(stream: InputStream): List<String> {
        val seen = LinkedHashSet<String>()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
            var lineNumber = 0
            for (raw in lines) {
                lineNumber++
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (!DOMAIN_REGEX.matches(line)) {
                    NMLog.w(NMSub.DNS, "$ASSET_NAME:$lineNumber invalid host shape '$line' — skipped")
                    continue
                }
                seen.add(line.lowercase())
                if (seen.size >= MAX_HOSTS) {
                    NMLog.w(NMSub.DNS, "$ASSET_NAME exceeded MAX_HOSTS=$MAX_HOSTS — remaining lines ignored")
                    break
                }
            }
        }
        NMLog.i(NMSub.DNS, "loaded ${seen.size} hosts from $ASSET_NAME")
        return seen.toList()
    }
}
