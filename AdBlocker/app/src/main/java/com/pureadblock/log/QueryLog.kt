package com.pureadblock.log

/**
 * Records DNS queries seen by the VPN so the user can inspect which domains an
 * app is contacting and add suspicious ones to the blocklist.
 *
 * The log is kept in memory with a bounded size; the most recent queries are
 * shown first.
 */
class QueryLog private constructor() {

    data class Entry(
        val domain: String,
        val blocked: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()

    val snapshot: List<Entry>
        get() = synchronized(lock) { entries.toList() }

    fun add(domain: String, blocked: Boolean) {
        synchronized(lock) {
            entries.addFirst(Entry(domain, blocked))
            while (entries.size > MAX_ENTRIES) {
                entries.removeLast()
            }
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private object Holder {
        val INSTANCE = QueryLog()
    }

    companion object {
        private const val MAX_ENTRIES = 500
        fun getInstance(): QueryLog = Holder.INSTANCE
    }
}
