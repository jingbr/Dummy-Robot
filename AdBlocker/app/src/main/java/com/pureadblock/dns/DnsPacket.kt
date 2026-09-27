package com.pureadblock.dns

import java.nio.ByteBuffer

/**
 * Minimal DNS packet parser focused on extracting the queried domain name from
 * the question section of a DNS query.
 *
 * Reference: RFC 1035.
 *
 * DNS message layout:
 *   Header  : 12 bytes
 *     ID(2) | Flags(2) | QDCOUNT(2) | ANCOUNT(2) | NSCOUNT(2) | ARCOUNT(2)
 *   Question section: QDCOUNT entries of
 *     QNAME (variable, label sequence terminated by 0) | QTYPE(2) | QCLASS(2)
 */
class DnsPacket(private val buffer: ByteBuffer) {

    val id: Int = buffer.getShort(0).toInt() and 0xFFFF
    val flags: Int = buffer.getShort(2).toInt() and 0xFFFF

    val qdCount: Int = buffer.getShort(4).toInt() and 0xFFFF
    val anCount: Int = buffer.getShort(6).toInt() and 0xFFFF
    val nsCount: Int = buffer.getShort(8).toInt() and 0xFFFF
    val arCount: Int = buffer.getShort(10).toInt() and 0xFFFF

    /** True if this is a response packet (QR bit set). */
    val isResponse: Boolean get() = (flags and 0x8000) != 0

    /** The first question's domain name, or null if not present / malformed. */
    val queryDomain: String?
        get() = parseDomainName()

    /**
     * Parses the QNAME starting at offset 12 (immediately after the header).
     * Supports label compression via pointers (0xC0 marker) per RFC 1035 4.1.4.
     */
    private fun parseDomainName(): String? {
        if (qdCount == 0) return null
        return try {
            val sb = StringBuilder(64)
            var offset = 12
            var jumped = false
            var maxJumps = 8
            var firstPointerOffset = -1

            while (maxJumps-- > 0) {
                if (offset >= buffer.remaining()) return null
                val length = buffer.get(offset).toInt() and 0xFF
                if (length == 0) {
                    if (!jumped) {
                        // pointer target reached; move offset past the zero byte
                    }
                    break
                }
                if ((length and 0xC0) == 0xC0) {
                    // compression pointer: 14-bit offset in next byte
                    if (offset + 1 >= buffer.remaining()) return null
                    val pointer = ((length and 0x3F) shl 8) or
                            (buffer.get(offset + 1).toInt() and 0xFF)
                    if (firstPointerOffset == -1) firstPointerOffset = offset + 2
                    offset = pointer
                    jumped = true
                    continue
                }
                offset++
                if (offset + length > buffer.remaining()) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until length) {
                    sb.append((buffer.get(offset + i).toInt() and 0xFF).toChar())
                }
                offset += length
            }

            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * Builds a minimal NXDOMAIN response for the given query [packet].
         * NXDOMAIN (rcode = 3) tells the client the domain does not exist, which
         * is how we "block" an advertisement domain.
         */
        fun buildNxDomainResponse(packet: ByteBuffer): ByteBuffer {
            val response = ByteBuffer.allocate(packet.remaining())
            // Copy header (12 bytes)
            for (i in 0 until 12) {
                response.put(packet.get(i))
            }
            // Set QR=1 (response), RCODE=3 (NXDOMAIN). Keep other flags as-is.
            val flags = packet.getShort(2).toInt() and 0xFFFF
            val respFlags = ((flags or 0x8000) and 0xFFF0) or 0x0003
            response.putShort(2, respFlags.toShort())
            // ANCOUNT, NSCOUNT, ARCOUNT = 0
            response.putShort(6, 0.toShort())
            response.putShort(8, 0.toShort())
            response.putShort(10, 0.toShort())
            // Copy the question section verbatim so the client can match it.
            response.position(12)
            packet.position(12)
            while (packet.hasRemaining()) {
                response.put(packet.get())
            }
            response.flip()
            return response
        }
    }
}

