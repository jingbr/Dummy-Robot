package com.qingxing.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val switchEnable = findViewById<Switch>(R.id.switchEnable)
        switchEnable.isChecked = Prefs.isEnabled(this)
        switchEnable.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
        }

        val editPkgs = findViewById<EditText>(R.id.editPackages)
        editPkgs.setText(Prefs.getPackages(this).joinToString("\n"))

        findViewById<Button>(R.id.btnSavePkgs).setOnClickListener {
            val pkgs = editPkgs.text.toString()
                .split("\n", ",", "，", " ", ";", "；")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            Prefs.setPackages(this, pkgs)
            Toast.makeText(this, "已保存 ${pkgs.size} 个目标应用", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnScreenTime).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.txtStatus).text =
            if (isServiceRunning()) "监控服务：运行中 ✓"
            else "监控服务：未开启（点下方按钮去授权）"

        renderRewards()

        val (interventions, exits, timeouts) = Prefs.getStats(this)
        findViewById<TextView>(R.id.txtStats).text =
            "今日统计\n意图确认 $interventions 次 · 主动放下 $exits 次 · 超时强退 $timeouts 次"
    }

    /** 渲染积分、等级进度、连击、徽章 */
    private fun renderRewards() {
        val status = RewardEngine.getStatus(this)

        findViewById<TextView>(R.id.txtPoints).text = status.points.toString()
        findViewById<TextView>(R.id.txtLevel).text =
            "Lv.${status.level + 1} ${status.levelTitle}"

        val toNext = RewardEngine.pointsToNextLevel(status.points)
        findViewById<ProgressBar>(R.id.progressLevel).progress = status.levelProgress
        findViewById<TextView>(R.id.txtToNext).text =
            if (toNext == 0) "已满级，你是时间的主人了"
            else "还差 $toNext 分升级"

        findViewById<TextView>(R.id.txtStreak).text =
            if (status.streak > 0) "🔥 连续清醒 ${status.streak} 天"
            else "连击已中断"

        val badgeLine = status.allBadges.joinToString("  ") { name ->
            if (name in status.badges) "● $name" else "○ $name"
        }
        findViewById<TextView>(R.id.txtBadges).text =
            "徽章 ${status.badges.size}/${status.allBadges.size}\n$badgeLine"
    }

    /** 检查本应用的无障碍服务是否已被用户开启 */
    private fun isServiceRunning(): Boolean {
        val expected = ComponentName(this, InterceptorService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
        }
        return false
    }
}
