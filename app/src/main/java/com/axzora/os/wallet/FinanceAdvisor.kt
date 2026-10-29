package com.axzora.os.wallet

import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * On-device financial intelligence for Happy Paisa — the "AI Finance Guardian"
 * from the roadmap. No server, no upload: it reads the local transaction ledger
 * and produces a real financial-health score, a spending breakdown, a simple
 * savings projection, and human-readable insights. Everything is deterministic
 * and bounded, and it degrades gracefully when there's little or no history.
 */
object FinanceAdvisor {

    /** A compact financial assessment derived from the transaction history. */
    data class Report(
        val score: Double,           // 0..100 financial health
        val balance: Double,
        val netDelta: Double,        // income - spend over the window
        val saveRatio: Double,       // saved / income (0..1)
        val spendingPerDay: Double,
        val spendCategories: Map<String, Double>,  // category -> total spent
        val predictedSavings30d: Double,           // pattern-based projection
        val insights: List<String>,
        val tiers: List<Tier>,
    )

    data class Tier(val label: String, val hp: Double, val fraction: Double)

    private const val WINDOW_DAYS = 30L

    fun analyze(balance: Double, txns: List<Txn>): Report {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_DAYS * 86400000L
        val inWindow = txns.filter { it.ts >= cutoff }
        val income = inWindow.filter { it.isCredit }.sumOf { abs(it.hp) }
        val spend = inWindow.filter { !it.isCredit }.sumOf { abs(it.hp) }

        val net = income - spend
        val saveRatio = if (income > 0) (net / income).coerceIn(0.0, 1.0) else 0.0
        val daysActive = (inWindow.map { it.ts }.maxOrNull()?.let { (it - cutoff) / 86400000L + 1 } ?: 0L)
            .coerceAtLeast(1L)
        val spendingPerDay = spend / daysActive

        // Category buckets by party/notes so the "where it went" line means something.
        val categories = LinkedHashMap<String, Double>()
        inWindow.filter { !it.isCredit }.forEach { t ->
            val key = categoryOf(t)
            categories[key] = (categories[key] ?: 0.0) + abs(t.hp)
        }
        val sortedCategories = categories.entries.sortedByDescending { it.value }
            .associate { it.key to it.value }

        // Simple projection: keep the observed save-rate going for 30 days.
        val dailyNet = if (daysActive > 0) net / daysActive else 0.0
        val predicted30 = (dailyNet * 30).coerceAtLeast(0.0)

        val score = healthScore(saveRatio, balance, spend, net)

        val insights = buildInsights(balance, income, spend, saveRatio, score, sortedCategories)

        val tiers = tierBreakdown(balance, txns)

        return Report(
            score = score,
            balance = balance,
            netDelta = net,
            saveRatio = saveRatio,
            spendingPerDay = spendingPerDay,
            spendCategories = sortedCategories,
            predictedSavings30d = predicted30,
            insights = insights,
            tiers = tiers,
        )
    }

    /** 0..100. Balances income being kept, a healthy reserve, and not overspending. */
    private fun healthScore(saveRatio: Double, balance: Double, spend: Double, net: Double): Double {
        var s = 50.0
        // Saving half or more is excellent; nothing saved caps the score low.
        s += (saveRatio * 45.0)
        // A small reserve in the wallet adds a little cushion.
        s += ((balance / (spend.coerceAtLeast(1.0)))).coerceIn(0.0, 1.0) * 5.0
        // Spending more than you take in drags it down.
        if (net < 0) s -= 10
        return s.coerceIn(0.0, 100.0)
    }

    private fun buildInsights(
        balance: Double, income: Double, spend: Double, saveRatio: Double,
        score: Double, cats: Map<String, Double>,
    ): List<String> {
        val out = mutableListOf<String>()
        when {
            score >= 85 -> out.add("Excellent financial health. You're keeping most of what comes in.")
            score >= 65 -> out.add("Good — a healthy saving habit. A little headroom to grow.")
            score >= 45 -> out.add("Steady. Trim one category and you'll save noticeably more.")
            else -> out.add("Spending nearly matches income. Set aside a small auto-save to build a buffer.")
        }
        if (income > 0) {
            out.add("You keep ${(saveRatio * 100).toInt()}% of income — %s.".format(
                if (saveRatio >= 0.5) "a strong save rate" else "could be higher"))
        }
        val top = cats.entries.firstOrNull()
        if (top != null) {
            out.add("Biggest spend: ${formatHp(top.value)} on ${top.key.lowercase()}.")
        }
        if (balance > 0) out.add("Wallet: ${formatHp(balance)}.")
        return out.take(5)
    }

    private fun tierBreakdown(balance: Double, txns: List<Txn>): List<Tier> {
        if (balance <= 0) return emptyList()
        // Group into five 20% tiers of the current balance, labelled by what's
        // (heuristically) a sensible allocation: an operator suggestion, not advice.
        val plan = listOf(
            "Essential" to 0.50, "Growth" to 0.25, "Savings" to 0.15, "Leisure" to 0.10,
        )
        return plan.map { (label, frac) -> Tier(label, balance * frac, frac) }
    }

    private fun categoryOf(t: Txn): String {
        val text = (t.note + " " + t.party).lowercase()
        return when {
            any(text, "rent", "loan", "emi", "bill") -> "Bills & rent"
            any(text, "grocer", "food", "restaurant", "eat", "snack", "market") -> "Food"
            any(text, "fuel", "petrol", "uber", "auto", "travel", "bus", "train") -> "Travel"
            any(text, "game", "entertain", "movie", "music", "concert") -> "Entertainment"
            any(text, "med", "doctor", "hospital", "pharma") -> "Health"
            else -> "Other"
        }
    }

    private fun any(s: String, vararg terms: String) = terms.any { s.contains(it) }

    private fun formatHp(hp: Double): String = String.format(Locale.ROOT, "%.0f HP", hp)

    /** A short "day N ago" label for the UI (e.g. "3d ago"). */
    fun whenLabel(ts: Long): String {
        val d = ((System.currentTimeMillis() - ts) / 86400000L).coerceAtLeast(0)
        return if (d == 0L) "today" else if (d == 1L) "yesterday" else "${d}d ago"
    }

    // expose the window for future callers
    fun windowDays(): Long = WINDOW_DAYS
}
