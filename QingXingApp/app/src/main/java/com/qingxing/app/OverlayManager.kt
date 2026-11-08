package com.qingxing.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * 管理四类浮窗（均为 TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限）：
 * 1. 意图确认弹窗  2. 到时/冷静期阻断屏  3. 右上角倒计时悬浮条  4. 奖励结算卡
 */
class OverlayManager(service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(service)
    private val res = service.resources
    private val handler = Handler(Looper.getMainLooper())

    private var intentView: View? = null
    private var blockView: View? = null
    private var timerView: View? = null
    private var timerText: TextView? = null
    private var rewardView: View? = null
    private val dismissReward = Runnable { removeReward() }

    private fun params(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    )

    // ---------- 意图确认弹窗 ----------

    fun showIntent(
        appLabel: String,
        quote: String,
        gapText: String?,
        onPick: (minutes: Int) -> Unit,
        onExit: () -> Unit
    ) {
        dismissIntent()
        val view = inflater.inflate(R.layout.overlay_intent, null)
        view.findViewById<TextView>(R.id.txtQuestion).text =
            res.getString(R.string.intent_question, appLabel)
        view.findViewById<TextView>(R.id.txtQuote).text =
            if (gapText != null) "$gapText\n$quote" else quote
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

    fun showBlock(message: String, subtitle: String, onExit: () -> Unit) {
        dismissBlock()
        val view = inflater.inflate(R.layout.overlay_block, null)
        view.findViewById<TextView>(R.id.txtBlock).text = message
        view.findViewById<TextView>(R.id.txtSub).text = subtitle
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

    // ---------- 奖励结算卡 ----------

    /**
     * 放下手机后的即时反馈。刻意做成不可点击、2.6 秒自动消失，
     * 避免"奖励页"本身变成新的刷屏时间。
     */
    fun showReward(reward: Reward) {
        removeReward()
        val view = inflater.inflate(R.layout.overlay_reward, null)
        val card = view.findViewById<View>(R.id.rewardCard)

        val gainText = view.findViewById<TextView>(R.id.txtGain)
        val tagText = view.findViewById<TextView>(R.id.txtTag)
        if (reward.gain > 0) {
            gainText.text = res.getString(R.string.reward_gain, reward.gain)
            tagText.text = if (reward.isCrit) res.getString(R.string.reward_crit)
            else res.getString(R.string.reward_normal)
        } else {
            gainText.visibility = View.GONE
            tagText.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.txtStreak).text =
            if (reward.streak > 0) res.getString(R.string.reward_streak, reward.streak)
            else res.getString(R.string.reward_streak_broken)

        val cardText = view.findViewById<TextView>(R.id.txtCard)
        view.findViewById<TextView>(R.id.txtCheer).text = reward.cheer
        reward.cardText?.let {
            cardText.text = res.getString(R.string.reward_card, it)
            cardText.visibility = View.VISIBLE
        }

        val badgeText = view.findViewById<TextView>(R.id.txtBadge)
        reward.newBadge?.let {
            badgeText.text = res.getString(R.string.reward_badge, it)
            badgeText.visibility = View.VISIBLE
        }

        val p = params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        ).apply { gravity = Gravity.TOP }

        addView(view, p)
        rewardView = view

        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()

        handler.postDelayed(dismissReward, 2600L)
    }

    fun removeReward() {
        handler.removeCallbacks(dismissReward)
        rewardView?.let { view ->
            view.animate().alpha(0f).setDuration(200).withEndAction {
                removeView(view)
            }.start()
        }
        rewardView = null
    }

    // ---------- 工具 ----------

    fun dismissAll() {
        dismissIntent()
        dismissBlock()
        dismissTimer()
        removeReward()
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
