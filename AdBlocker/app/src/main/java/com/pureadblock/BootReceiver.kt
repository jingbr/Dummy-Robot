package com.pureadblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pureadblock.vpn.AdBlockVpnService

/**
 * Restarts the ad-blocking VPN after the device boots if it was enabled.
 * For simplicity this sample always starts the VPN on boot; you can gate it on
 * a SharedPreferences flag to remember the user's last toggle state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val startIntent = Intent(context, AdBlockVpnService::class.java)
            context.startForegroundService(startIntent)
        }
    }
}
