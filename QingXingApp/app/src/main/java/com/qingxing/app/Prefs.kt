package com.qingxing.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SharedPreferences 存取：开关、目标包名、冷静期、按天统计、奖励进度。 */
object Prefs {

    private const val NAME = "qingxing"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_STAT_DATE = "stat_date"
    private const val KEY_STAT_INTERVENTIONS = "stat_interventions"
    private const val KEY_STAT_EXITS = "stat_exits"
    private const val KEY_STAT_TIMEOUTS = "stat_timeouts"

    // 奖励系统
    private const val KEY_POINTS = "points"
    private const val KEY_STREAK = "streak"
    private const val KEY_STREAK_DATE = "streak_date"
    private const val KEY_STREAK_FAILED = "streak_failed"
    private const val KEY_TOTAL_EXITS = "total_exits"
    private const val KEY_CRITS = "crits"
    private const val KEY_CARDS = "cards_unlocked"
    private const val KEY_BADGES = "badges"
    private const val KEY_LAST_AWARD_AT = "last_award_at"

    /** 超时强退后的冷静期时长（分钟） */
    const val COOLDOWN_MINUTES = 20L

    /** 默认拦截的短视频/信息流应用包名 */
    val DEFAULT_PACKAGES = setOf(
        "com.ss.android.ugc.aweme",      // 抖音
        "com.ss.android.ugc.aweme.lite", // 抖音极速版
        "com.smile.gifmaker",            // 快手
        "com.kuaishou.nebula",           // 快手极速版
        "com.xingin.xhs",                // 小红书
        "tv.danmaku.bili",               // 哔哩哔哩
        "com.sina.weibo",                // 微博
        "com.zhihu.android",             // 知乎
        "com.ss.android.article.news"    // 今日头条
    )

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun today(): String = dayFormat.format(Date())
    private fun yesterday(): String = dayFormat.format(Date(System.currentTimeMillis() - 86_400_000L))

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getPackages(context: Context): Set<String> =
        sp(context).getStringSet(KEY_PACKAGES, null) ?: DEFAULT_PACKAGES

    fun setPackages(context: Context, packages: Set<String>) {
        sp(context).edit().putStringSet(KEY_PACKAGES, packages).apply()
    }

    fun getCooldownUntil(context: Context): Long =
        sp(context).getLong(KEY_COOLDOWN_UNTIL, 0L)

    fun setCooldownUntil(context: Context, timestamp: Long) {
        sp(context).edit().putLong(KEY_COOLDOWN_UNTIL, timestamp).apply()
    }

    // ---------- 按天统计 ----------

    private fun ensureToday(context: Context): SharedPreferences {
        val prefs = sp(context)
        if (prefs.getString(KEY_STAT_DATE, null) != today()) {
            prefs.edit()
                .putString(KEY_STAT_DATE, today())
                .putInt(KEY_STAT_INTERVENTIONS, 0)
                .putInt(KEY_STAT_EXITS, 0)
                .putInt(KEY_STAT_TIMEOUTS, 0)
                .apply()
        }
        return prefs
    }

    private fun bump(context: Context, key: String) {
        val prefs = ensureToday(context)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun bumpInterventions(context: Context) = bump(context, KEY_STAT_INTERVENTIONS)
    fun bumpExits(context: Context) = bump(context, KEY_STAT_EXITS)
    fun bumpTimeouts(context: Context) = bump(context, KEY_STAT_TIMEOUTS)

    fun getStats(context: Context): Triple<Int, Int, Int> {
        val prefs = ensureToday(context)
        return Triple(
            prefs.getInt(KEY_STAT_INTERVENTIONS, 0),
            prefs.getInt(KEY_STAT_EXITS, 0),
            prefs.getInt(KEY_STAT_TIMEOUTS, 0)
        )
    }

    // ---------- 积分 ----------

    fun getPoints(context: Context): Int = sp(context).getInt(KEY_POINTS, 0)

    fun addPoints(context: Context, delta: Int) {
        val prefs = sp(context)
        prefs.edit().putInt(KEY_POINTS, (prefs.getInt(KEY_POINTS, 0) + delta).coerceAtLeast(0)).apply()
    }

    // ---------- 连击 ----------

    fun getStreak(context: Context): Int = sp(context).getInt(KEY_STREAK, 0)

    /**
     * 跨天推进连击：昨天保持清醒则 +1，否则从 1 重新开始。
     * 同一天内重复调用不改变数值。
     */
    fun refreshStreak(context: Context): Int {
        val prefs = sp(context)
        val last = prefs.getString(KEY_STREAK_DATE, "")
        if (last == today()) return prefs.getInt(KEY_STREAK, 0)

        val failed = prefs.getBoolean(KEY_STREAK_FAILED, false)
        val streak = if (last == yesterday() && !failed) {
            prefs.getInt(KEY_STREAK, 0) + 1
        } else {
            1
        }
        prefs.edit()
            .putInt(KEY_STREAK, streak)
            .putString(KEY_STREAK_DATE, today())
            .putBoolean(KEY_STREAK_FAILED, false)
            .apply()
        return streak
    }

    /** 超时当天立刻清零，且今天不能再被算作清醒日 */
    fun breakStreak(context: Context) {
        sp(context).edit()
            .putInt(KEY_STREAK, 0)
            .putString(KEY_STREAK_DATE, today())
            .putBoolean(KEY_STREAK_FAILED, true)
            .apply()
    }

    // ---------- 累计成就数据 ----------

    fun getTotalExits(context: Context): Int = sp(context).getInt(KEY_TOTAL_EXITS, 0)

    fun bumpTotalExits(context: Context) {
        val prefs = sp(context)
        prefs.edit().putInt(KEY_TOTAL_EXITS, prefs.getInt(KEY_TOTAL_EXITS, 0) + 1).apply()
    }

    fun getCrits(context: Context): Int = sp(context).getInt(KEY_CRITS, 0)

    fun bumpCrits(context: Context) {
        val prefs = sp(context)
        prefs.edit().putInt(KEY_CRITS, prefs.getInt(KEY_CRITS, 0) + 1).apply()
    }

    fun getCardsUnlocked(context: Context): Int = sp(context).getInt(KEY_CARDS, 0)

    fun setCardsUnlocked(context: Context, count: Int) {
        sp(context).edit().putInt(KEY_CARDS, count).apply()
    }

    fun getBadges(context: Context): Set<String> =
        sp(context).getStringSet(KEY_BADGES, emptySet()) ?: emptySet()

    fun setBadges(context: Context, badges: Set<String>) {
        sp(context).edit().putStringSet(KEY_BADGES, badges).apply()
    }

    /** 上次获得奖励的时间戳，用于防止反复开关 App 刷分 */
    fun getLastAwardAt(context: Context): Long = sp(context).getLong(KEY_LAST_AWARD_AT, 0L)

    fun setLastAwardAt(context: Context, timestamp: Long) {
        sp(context).edit().putLong(KEY_LAST_AWARD_AT, timestamp).apply()
    }
}
