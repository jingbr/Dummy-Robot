package com.qingxing.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * 管理三类浮窗（均为 TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限）：
 * 1. 意图确认弹窗  2. 到时/冷静期阻断屏  3. 右上角倒计时悬浮条
 */
class OverlayManager(service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(service)
    private val res = service.resources

    private var intentView: View? = null
    private var blockView: View? = null
    private var timerView: View? = null
    private var timerText: TextView? = null

    private fun params(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    )

    // ---------- 意图确认弹窗 ----------

    fun showIntent(appLabel: String, onPick: (minutes: Int) -> Unit, onExit: () -> Unit) {
        dismissIntent()
        val view = inflater.inflate(R.layout.overlay_intent, null)
        view.findViewById<TextView>(R.id.txtQuestion).text =
            res.getString(R.string.intent_question, appLabel)
        bindPick(view, R.id.btnMin5, 5, onPick)
        bindPick(view, R.id.btnMin10, 10, onPick)
        bindPick(view, R.id.btnMin15, 15, onPick)
        view.findViewById<Button>(R.id.btnExit).setOnClickListener { onExit() }

        val p = params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }
        addView(view, p)
        intentView = view
    }

    private fun bindPick(view: View, id: Int, minutes: Int, onPick: (Int) -> Unit) {
        view.findViewById<Button>(id).setOnClickListener { onPick(minutes) }
    }

    fun dismissIntent() {
        intentView?.let { removeView(it) }
        intentView = null
    }

    // ---------- 阻断屏 ----------

    fun showBlock(message: String, onExit: () -> Unit) {
        dismissBlock()
        val view = inflater.inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.txtBlock).text = message
        view.findViewById<Button>(R.id.btnHome).setOnClickListener { onExit() }

        val p = params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0
        ).apply { gravity = Gravity.CENTER }
        addView(view, p)
        blockView = view
    }

    fun dismissBlock() {
        blockView?.let { removeView(it) }
        blockView = null
    }

    // ---------- 倒计时悬浮条 ----------

    fun showTimer() {
        if (timerView != null) return
        val view = inflater.inflate(R.layout.overlay_timer, null)
        timerText = view.findViewById(R.id.txtTimer)

        val p = params(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        }
        addView(view, p)
        timerView = view
    }

    fun updateTimer(remainingMs: Long) {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
        timerText?.text = res.getString(R.string.timer_format, totalSec / 60, totalSec % 60)
    }

    fun dismissTimer() {
        timerView?.let { removeView(it) }
        timerView = null
        timerText = null
    }

    // ---------- 工具 ----------

    fun dismissAll() {
        dismissIntent()
        dismissBlock()
        dismissTimer()
    }

    private fun addView(view: View, p: WindowManager.LayoutParams) {
        try {
            wm.addView(view, p)
        } catch (_: Exception) {
        }
    }

    private fun removeView(view: View) {
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }
}
