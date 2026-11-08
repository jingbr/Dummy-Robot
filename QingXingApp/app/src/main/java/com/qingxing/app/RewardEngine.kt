package com.qingxing.app

import android.content.Context

/** 一次"主动放下"的奖励结算结果 */
data class Reward(
    val gain: Int,
    val isCrit: Boolean,
    val totalPoints: Int,
    val streak: Int,
    val level: Int,
    val levelTitle: String,
    val levelProgress: Int,
    val cardText: String?,
    val newBadge: String?
)

/** 一次超时的惩罚结算结果 */
data class Penalty(
    val lost: Int,
    val totalPoints: Int,
    val brokenStreak: Int
)

/** 主界面展示的累计状态 */
data class Status(
    val points: Int,
    val streak: Int,
    val level: Int,
    val levelTitle: String,
    val levelProgress: Int,
    val badges: List<String>,       // 已解锁
    val allBadges: List<String>     // 全部（含未解锁，用于展示）
)

/**
 * 把"刷手机 App 让人上瘾"的四套机制反过来用：
 * 1. 即时反馈：放下手机的那一刻立刻加分
 * 2. 可变奖励：15% 概率暴击三倍 + 随机解锁语录卡
 * 3. 连击 + 损失厌恶：连续清醒天数，一次超时立刻清零
 * 4. 进度条：看着离下一级只差一点
 */
object RewardEngine {

    private val LEVEL_THRESHOLDS = intArrayOf(0, 100, 250, 450, 700, 1000, 1400, 1900, 2500)
    private val LEVEL_TITLES = arrayOf(
        "新手上路", "初醒", "有觉知的人", "掌舵者", "时间主人",
        "专注行者", "心流农夫", "清醒大师", "自由人"
    )

    private const val BASE_POINTS = 10
    private const val CRIT_CHANCE = 15          // 暴击概率（百分比）
    private const val CRIT_MULTIPLIER = 3
    private const val PENALTY_POINTS = 5
    private const val STREAK_BONUS_CAP = 10     // 连击加成封顶天数
    private const val MIN_AWARD_INTERVAL_MS = 60_000L  // 防刷：两次奖励至少间隔 1 分钟

    // ---------- 对外：结算 ----------

    fun awardRestraint(context: Context): Reward {
        val now = System.currentTimeMillis()
        // 反复"打开→立刻退出"不该刷出积分
        if (now - Prefs.getLastAwardAt(context) < MIN_AWARD_INTERVAL_MS) {
            return silentReward(context)
        }
        Prefs.setLastAwardAt(context, now)

        val streak = Prefs.refreshStreak(context)

        val streakBonus = streak.coerceAtMost(STREAK_BONUS_CAP) * 2
        val isCrit = (0 until 100).random() < CRIT_CHANCE
        var gain = BASE_POINTS + streakBonus
        if (isCrit) gain *= CRIT_MULTIPLIER

        Prefs.addPoints(context, gain)
        Prefs.bumpExits(context)
        Prefs.bumpTotalExits(context)
        if (isCrit) Prefs.bumpCrits(context)

        val card = if (isCrit) unlockCard(context) else null
        val badge = checkNewBadges(context)

        val points = Prefs.getPoints(context)
        val level = levelOf(points)
        return Reward(
            gain = gain,
            isCrit = isCrit,
            totalPoints = points,
            streak = streak,
            level = level,
            levelTitle = LEVEL_TITLES[level],
            levelProgress = progressOf(points, level),
            cardText = card,
            newBadge = badge
        )
    }

    fun applyTimeout(context: Context): Penalty {
        val broken = Prefs.getStreak(context)
        val lost = PENALTY_POINTS.coerceAtMost(Prefs.getPoints(context))
        Prefs.addPoints(context, -lost)
        Prefs.breakStreak(context)
        return Penalty(lost, Prefs.getPoints(context), broken)
    }

    /** 被防刷拦截时的空结算：不加分、不弹窗 */
    private fun silentReward(context: Context): Reward {
        val points = Prefs.getPoints(context)
        val level = levelOf(points)
        return Reward(
            gain = 0,
            isCrit = false,
            totalPoints = points,
            streak = Prefs.getStreak(context),
            level = level,
            levelTitle = LEVEL_TITLES[level],
            levelProgress = progressOf(points, level),
            cardText = null,
            newBadge = null
        )
    }

    fun getStatus(context: Context): Status {
        val points = Prefs.getPoints(context)
        val level = levelOf(points)
        val unlocked = Prefs.getBadges(context).toList()
        return Status(
            points = points,
            streak = Prefs.getStreak(context),
            level = level,
            levelTitle = LEVEL_TITLES[level],
            levelProgress = progressOf(points, level),
            badges = unlocked,
            allBadges = ALL_BADGES
        )
    }

    /** 下一级还差多少分；已满级返回 0 */
    fun pointsToNextLevel(points: Int): Int {
        val level = levelOf(points)
        return if (level >= LEVEL_THRESHOLDS.lastIndex) 0
        else LEVEL_THRESHOLDS[level + 1] - points
    }

    // ---------- 等级 ----------

    private fun levelOf(points: Int): Int {
        var level = 0
        for (i in LEVEL_THRESHOLDS.indices) {
            if (points >= LEVEL_THRESHOLDS[i]) level = i
        }
        return level
    }

    private fun progressOf(points: Int, level: Int): Int {
        if (level >= LEVEL_THRESHOLDS.lastIndex) return 100
        val from = LEVEL_THRESHOLDS[level]
        val to = LEVEL_THRESHOLDS[level + 1]
        return ((points - from) * 100 / (to - from)).coerceIn(0, 100)
    }

    // ---------- 语录卡 ----------

    private fun unlockCard(context: Context): String {
        val quotes = context.resources.getStringArray(R.array.quotes)
        val owned = Prefs.getCardsUnlocked(context)
        return if (owned < quotes.size) {
            Prefs.setCardsUnlocked(context, owned + 1)
            quotes[owned]
        } else {
            quotes[(quotes.indices).random()]
        }
    }

    // ---------- 徽章 ----------

    private val ALL_BADGES = listOf(
        "初次清醒", "十次转身", "五十次转身", "三日清醒",
        "一周掌控", "五百清醒分", "欧皇体质", "语录收藏家"
    )

    private fun checkNewBadges(context: Context): String? {
        val unlocked = Prefs.getBadges(context).toMutableSet()
        val candidates = listOf(
            "初次清醒" to (Prefs.getTotalExits(context) >= 1),
            "十次转身" to (Prefs.getTotalExits(context) >= 10),
            "五十次转身" to (Prefs.getTotalExits(context) >= 50),
            "三日清醒" to (Prefs.getStreak(context) >= 3),
            "一周掌控" to (Prefs.getStreak(context) >= 7),
            "五百清醒分" to (Prefs.getPoints(context) >= 500),
            "欧皇体质" to (Prefs.getCrits(context) >= 5),
            "语录收藏家" to (Prefs.getCardsUnlocked(context) >= 12)
        )
        var firstNew: String? = null
        candidates.forEach { (title, ok) ->
            if (ok && title !in unlocked) {
                unlocked.add(title)
                if (firstNew == null) firstNew = title
            }
        }
        if (firstNew != null) Prefs.setBadges(context, unlocked)
        return firstNew
    }
}
