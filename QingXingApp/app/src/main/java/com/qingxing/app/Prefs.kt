package com.qingxing.app

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SharedPreferences 存取：开关、目标包名、冷静期、按天统计。 */
object Prefs {

    private const val NAME = "qingxing"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
    private const val KEY_STAT_DATE = "stat_date"
    private const val KEY_STAT_INTERVENTIONS = "stat_interventions"
    private const val KEY_STAT_EXITS = "stat_exits"
    private const val KEY_STAT_TIMEOUTS = "stat_timeouts"

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

    /** 跨天时清零统计 */
    private fun ensureToday(context: Context): SharedPreferences {
        val prefs = sp(context)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString(KEY_STAT_DATE, null) != today) {
            prefs.edit()
                .putString(KEY_STAT_DATE, today)
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

    /** 今日三元组：意图确认次数、主动放下次数、超时强退次数 */
    fun getStats(context: Context): Triple<Int, Int, Int> {
        val prefs = ensureToday(context)
        return Triple(
            prefs.getInt(KEY_STAT_INTERVENTIONS, 0),
            prefs.getInt(KEY_STAT_EXITS, 0),
            prefs.getInt(KEY_STAT_TIMEOUTS, 0)
        )
    }
}
