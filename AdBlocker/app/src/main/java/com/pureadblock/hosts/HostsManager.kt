package com.pureadblock.hosts

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Manages the advertising / tracking domain blocklist.
 *
 * The blocklist is loaded from a bundled hosts file (res/raw/hosts) plus a small
 * hard-coded seed list for safety. Matching is done on exact domain and on any
 * subdomain of a blocked entry (so "ads.example.com" is blocked if "example.com"
 * or "ads.example.com" is in the list).
 */
class HostsManager private constructor() {

    private val blockedDomains: MutableSet<String> = HashSet(4096)

    val size: Int
        get() = blockedDomains.size

    private object Holder {
        val INSTANCE = HostsManager()
    }

    companion object {
        fun getInstance(): HostsManager = Holder.INSTANCE

        // Minimal seed list shipped in code so the app blocks something even if
        // the bundled hosts file is stripped out.
        private val SEED_DOMAINS = arrayOf(
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "googletagmanager.com",
            "googletagservices.com",
            "google-analytics.com",
            "facebook.net",
            "facebook.com",
            "fbcdn.net",
            "adnxs.com",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "amazon-adsystem.com",
            "scorecardresearch.com",
            "quantserve.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "criteo.com",
            "moatads.com",
            "adsrvr.org",
            "adform.net",
            "adroll.com",
            "bizographics.com",
            "bluekai.com",
            "tribalfusion.com",
            "exponential.com",
            "casalemedia.com",
            "yieldmanager.com",
            "advertising.com",
            "mathtag.com",
            "dotomi.com",
            "adsafeprotected.com"
        )
    }

    @Synchronized
    fun load(context: Context) {
        if (blockedDomains.isNotEmpty()) return
        SEED_DOMAINS.forEach { blockedDomains.add(it.lowercase()) }
        loadFromRawResource(context)
    }

    private fun loadFromRawResource(context: Context) {
        try {
            val input = context.resources.openRawResource(
                context.resources.getIdentifier("hosts", "raw", context.packageName)
            )
            BufferedReader(InputStreamReader(input)).use { reader ->
                reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachLine
                    // hosts file format: "0.0.0.0 domain.com" or "127.0.0.1 domain.com"
                    val parts = trimmed.split("\\s+".toRegex())
                    val domain = if (parts.size >= 2) parts[1] else parts[0]
                    if (domain.isNotBlank() && !domain.startsWith("#")) {
                        blockedDomains.add(domain.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            // Resource may be absent; seed list still covers common ad networks.
        }
    }

    /**
     * Returns true if [domain] (or any of its parent domains) is on the blocklist.
     */
    @Synchronized
    fun isBlocked(domain: String): Boolean {
        if (domain.isBlank()) return false
        val d = domain.lowercase().trimEnd('.')
        if (blockedDomains.contains(d)) return true
        // Walk up the domain labels: a.b.example.com -> b.example.com -> example.com
        var idx = d.indexOf('.')
        while (idx != -1) {
            val parent = d.substring(idx + 1)
            if (blockedDomains.contains(parent)) return true
            idx = d.indexOf('.', idx + 1)
        }
        return false
    }

    @Synchronized
    fun addDomain(domain: String) {
        blockedDomains.add(domain.lowercase().trimEnd('.'))
    }

    @Synchronized
    fun removeDomain(domain: String) {
        blockedDomains.remove(domain.lowercase().trimEnd('.'))
    }

    @Synchronized
    fun clear() {
        blockedDomains.clear()
    }
}
