package com.pureadblock

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pureadblock.databinding.ActivityMainBinding
import com.pureadblock.vpn.AdBlockVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startAdBlockVpn()
        } else {
            binding.switchVpn.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchVpn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestVpnPermission()
            } else {
                stopAdBlockVpn()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startAdBlockVpn()
        }
    }

    private fun startAdBlockVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java)
        startForegroundService(intent)
        binding.textStatus.text = getString(R.string.status_active)
        binding.textStatus.setTextColor(getColor(R.color.green_active))
    }

    private fun stopAdBlockVpn() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        startService(intent)
        binding.textStatus.text = getString(R.string.status_inactive)
        binding.textStatus.setTextColor(getColor(R.color.gray_inactive))
    }

    private fun updateStats() {
        binding.textBlockedCount.text = getString(
            R.string.blocked_count,
            AdBlockVpnService.blockedCount
        )
    }
}
