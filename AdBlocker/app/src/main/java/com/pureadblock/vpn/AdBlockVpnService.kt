package com.pureadblock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pureadblock.MainActivity
import com.pureadblock.R
import com.pureadblock.dns.DnsPacket
import com.pureadblock.hosts.HostsManager
import com.pureadblock.log.QueryLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local VPN service that intercepts DNS queries and blocks advertisement /
 * tracking domains by forging NXDOMAIN responses. Non-blocked queries are
 * forwarded to a real upstream DNS server.
 *
 * Works without root. Only DNS (UDP port 53) traffic is inspected; all other
 * traffic is passed through untouched by this implementation (it is simply not
 * forwarded because we only handle DNS here — see [runVpnLoop]).
 */
class AdBlockVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnJob: Job? = null

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        HostsManager.getInstance().load(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) {
            return START_STICKY
        }
        isServiceRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        vpnJob = scope.launch { runVpnLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isServiceRunning = false
        vpnJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun stopVpn() {
        running.set(false)
        isServiceRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(IPV4_VPN_ADDRESS, 24)
            // Only route the VPN's own DNS server address through the tunnel.
            // This keeps all other (non-DNS) traffic on the device's normal
            // network, so browsing and apps keep working while DNS queries are
            // intercepted for ad blocking.
            .addRoute(IPV4_DNS_ADDRESS, 32)
            .addDnsServer(IPV4_DNS_ADDRESS)
            .addDisallowedApplication(applicationContext.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setMtu(1500)
        }
        return builder.establish()
    }

    private suspend fun runVpnLoop() = withContext(Dispatchers.IO) {
        val pfd = establishVpn() ?: run {
            Log.e(TAG, "VPN establish() returned null")
            running.set(false)
            return@withContext
        }
        vpnInterface = pfd

        val fileIn = FileInputStream(pfd.fileDescriptor)
        val fileOut = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteBuffer.allocate(32767)
        val socket = DatagramSocket().apply { soTimeout = 2000 }

        try {
            while (running.get()) {
                val length = try {
                    fileIn.read(packet.array())
                } catch (e: Exception) {
                    break
                }
                if (length <= 0) continue

                packet.position(0)
                packet.limit(length)

                val parsed = parseIpPacket(packet) ?: continue
                if (!parsed.isUdpDns) {
                    // Only DNS traffic to the VPN DNS address is routed into the
                    // tunnel, so we should never see other packets here. Skip
                    // anything unexpected.
                    continue
                }

                val dnsPayload = parsed.dnsPayload ?: continue
                val query = DnsPacket(dnsPayload)
                val domain = query.queryDomain

                if (domain != null) {
                    val blocked = HostsManager.getInstance().isBlocked(domain)
                    QueryLog.getInstance().add(domain, blocked)
                    if (blocked) {
                        val nxdomain = DnsPacket.buildNxDomainResponse(dnsPayload.duplicate())
                        sendDnsResponse(fileOut, parsed, nxdomain)
                        blockedCount++
                        continue
                    }
                }

                // Forward to upstream DNS and relay the answer back.
                try {
                    val upstream = InetAddress.getByName(UPSTREAM_DNS)
                    val bytes = ByteArray(dnsPayload.remaining())
                    dnsPayload.get(bytes)
                    val request = DatagramPacket(bytes, bytes.size, upstream, 53)
                    socket.send(request)

                    val buf = ByteArray(4096)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    val answer = ByteBuffer.wrap(buf, 0, response.length)
                    sendDnsResponse(fileOut, parsed, answer)
                } catch (e: Exception) {
                    // timeouts / network errors are non-fatal
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN loop error", e)
        } finally {
            socket.close()
            try {
                fileIn.close()
                fileOut.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Forges a DNS response packet by wrapping the [dnsAnswer] payload in a UDP
     * and IPv4 header that swaps the original packet's source/destination.
     */
    private fun sendDnsResponse(
        out: FileOutputStream,
        request: ParsedIpPacket,
        dnsAnswer: ByteBuffer
    ) {
        val answer = ByteArray(dnsAnswer.remaining())
        dnsAnswer.get(answer)

        // UDP header: src port = 53 (DNS), dst port = original src port, length, checksum 0
        val udpLen = 8 + answer.size
        val udp = ByteBuffer.allocate(udpLen)
        udp.putShort(53.toShort())                    // source port
        udp.putShort(request.srcPort.toShort())       // dest port
        udp.putShort(udpLen.toShort())                // length
        udp.putShort(0.toShort())                     // checksum (0 = none for UDP over IPv4)
        udp.put(answer)

        // IPv4 header: swap src/dst, protocol UDP, recompute checksum.
        val ipTotalLen = 20 + udpLen
        val ip = ByteBuffer.allocate(ipTotalLen)
        ip.put(((4 shl 4) or 5).toByte())             // version=4, IHL=5
        ip.put(0.toByte())                            // DSCP/ECN
        ip.putShort(ipTotalLen.toShort())             // total length
        ip.putShort(0.toShort())                      // identification
        ip.putShort(0.toShort())                      // flags/fragment offset
        ip.put(64.toByte())                           // TTL
        ip.put(17.toByte())                           // protocol UDP
        ip.putShort(0.toShort())                      // header checksum placeholder
        ip.put(request.dstIp)                         // source = original DNS server (VPN DNS)
        ip.put(request.srcIp)                         // destination = original client
        val checksum = computeIpChecksum(ip.array(), 0, 20)
        ip.putShort(10, checksum)

        ip.put(udp.array())
        try {
            out.write(ip.array())
        } catch (_: Exception) {
        }
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0L
        var i = 0
        while (i < length) {
            val word = ((data[offset + i].toInt() and 0xFF) shl 8) or
                    (data[offset + i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private data class ParsedIpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val isUdpDns: Boolean,
        val dnsPayload: ByteBuffer?
    )

    private fun parseIpPacket(buf: ByteBuffer): ParsedIpPacket? {
        if (buf.remaining() < 28) return null
        val start = buf.position()
        val versionIhl = buf.get(start).toInt()
        val version = versionIhl shr 4
        if (version != 4) return null
        val ihl = (versionIhl and 0x0F) * 4
        if (buf.remaining() < ihl + 8) return null
        val protocol = buf.get(start + 9).toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        buf.position(start + 12)
        buf.get(srcIp)
        buf.get(dstIp)

        val udpStart = start + ihl
        val srcPort = (buf.getShort(udpStart).toInt()) and 0xFFFF
        val dstPort = (buf.getShort(udpStart + 2).toInt()) and 0xFFFF
        val udpLength = (buf.getShort(udpStart + 4).toInt()) and 0xFFFF

        // DNS traffic is directed at the VPN's DNS address on port 53.
        val isDns = dstPort == 53

        if (!isDns) {
            return ParsedIpPacket(srcIp, dstIp, srcPort, false, null)
        }

        val dnsStart = udpStart + 8
        val dnsLength = (udpLength - 8).coerceAtMost(buf.remaining() - (dnsStart - start))
        if (dnsLength <= 0) return null

        val payload = ByteBuffer.allocate(dnsLength)
        val oldPos = buf.position()
        buf.position(dnsStart)
        for (i in 0 until dnsLength) payload.put(buf.get())
        buf.position(oldPos)
        payload.flip()
        return ParsedIpPacket(srcIp, dstIp, srcPort, true, payload)
    }

    private fun buildNotification(): Notification {
        val channelId = "adblock_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ad Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while ad blocking VPN is active"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AdBlockVpnService"
        const val ACTION_STOP = "com.pureadblock.STOP_VPN"
        const val IPV4_VPN_ADDRESS = "10.6.6.6"
        const val IPV4_DNS_ADDRESS = "10.6.6.7"
        const val UPSTREAM_DNS = "1.1.1.1"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var blockedCount: Long = 0L

        @Volatile
        var isServiceRunning: Boolean = false

        fun isRunning(): Boolean = isServiceRunning
    }
}
