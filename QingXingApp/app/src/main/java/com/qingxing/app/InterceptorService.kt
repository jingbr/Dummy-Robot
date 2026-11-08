package com.qingxing.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 核心状态机：
 *   无会话 --(打开目标 App)--> 意图确认弹窗
 *   选定时长 --> 会话中（悬浮倒计时）
 *   到时 --> 阻断屏 + 回桌面 + 进入冷静期
 *   冷静期内再打开 --> 直接阻断
 *   中途离开目标 App --> 记一次"主动放下"
 */
class InterceptorService : AccessibilityService() {

    companion object {
        /** 这些系统包名的窗口事件不参与判断（通知栏等） */
        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")
        private const val AUTO_HOME_DELAY_MS = 3000L
    }

    private lateinit var overlay: OverlayManager
    private val handler = Handler(Looper.getMainLooper())

    /** 进行中的会话：目标包名 + 结束时间戳 */
    private var sessionPkg: String? = null
    private var sessionEndAt = 0L

    /** 正在等待用户确认意图的目标包名 */
    private var awaitingPkg: String? = null
    private var blockShowing = false

    private val ticker = object : Runnable {
        override fun run() {
            val pkg = sessionPkg ?: return
            val remaining = sessionEndAt - System.currentTimeMillis()
            if (remaining <= 0) {
                onSessionTimeout(pkg)
            } else {
                overlay.updateTimer(remaining)
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onServiceConnected() {
        overlay = OverlayManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null ||
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (!Prefs.isEnabled(this)) {
            reset()
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return

        // 用户没选就离开了正在确认意图的 App（如按了 Home）
        val awaiting = awaitingPkg
        if (awaiting != null && pkg != awaiting) {
            overlay.dismissIntent()
            awaitingPkg = null
        }

        val isTarget = Prefs.getPackages(this).contains(pkg)

        // 冷静期内打开目标 App：直接阻断
        if (isTarget && System.currentTimeMillis() < Prefs.getCooldownUntil(this)) {
            showBlock(getString(R.string.block_cooldown))
            return
        }

        if (isTarget) {
            val current = sessionPkg
            when {
                current == null -> {
                    if (awaitingPkg == null) {
                        awaitingPkg = pkg
                        showIntentOverlay(pkg)
                    }
                }
                current != pkg -> {
                    // 从一个目标 App 跳到另一个：旧会话静默结束，重新确认意图
                    endSession(countAsExit = false)
                    awaitingPkg = pkg
                    showIntentOverlay(pkg)
                }
            }
        } else {
            // 离开目标 App
            if (sessionPkg != null) endSession(countAsExit = true)
            if (blockShowing) {
                overlay.dismissBlock()
                blockShowing = false
            }
        }
    }

    private fun showIntentOverlay(pkg: String) {
        overlay.showIntent(
            appLabel = appLabel(pkg),
            onPick = { minutes ->
                awaitingPkg = null
                overlay.dismissIntent()
                startSession(pkg, minutes)
            },
            onExit = {
                awaitingPkg = null
                overlay.dismissIntent()
                Prefs.bumpExits(this)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        )
    }

    private fun startSession(pkg: String, minutes: Int) {
        sessionPkg = pkg
        sessionEndAt = System.currentTimeMillis() + minutes * 60_000L
        Prefs.bumpInterventions(this)
        overlay.showTimer()
        overlay.updateTimer(minutes * 60_000L)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun endSession(countAsExit: Boolean) {
        sessionPkg = null
        handler.removeCallbacks(ticker)
        overlay.dismissTimer()
        if (countAsExit) Prefs.bumpExits(this)
    }

    private fun onSessionTimeout(pkg: String) {
        sessionPkg = null
        handler.removeCallbacks(ticker)
        overlay.dismissTimer()
        Prefs.bumpTimeouts(this)
        Prefs.setCooldownUntil(
            this,
            System.currentTimeMillis() + Prefs.COOLDOWN_MINUTES * 60_000L
        )
        showBlock(getString(R.string.block_timeout))
    }

    private fun showBlock(message: String) {
        if (blockShowing) return
        blockShowing = true
        overlay.showBlock(message) {
            overlay.dismissBlock()
            blockShowing = false
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
        // 3 秒后用户还没点按钮，自动回桌面
        handler.postDelayed({
            if (blockShowing) {
                overlay.dismissBlock()
                blockShowing = false
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, AUTO_HOME_DELAY_MS)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun reset() {
        sessionPkg = null
        awaitingPkg = null
        blockShowing = false
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.dismissAll()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        reset()
        super.onDestroy()
    }
}
