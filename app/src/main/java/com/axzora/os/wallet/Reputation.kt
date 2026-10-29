package com.axzora.os.wallet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.reputationStore by preferencesDataStore("axzora_reputation")
private val KEY_SCORE = intPreferencesKey("score")
private val KEY_LOG = stringPreferencesKey("contributions")
private val KEY_CATS = stringPreferencesKey("categories")

/** Contribution categories that make up the Human Value Score. */
val CONTRIBUTION_CATEGORIES = listOf("knowledge", "creativity", "community", "environmental", "reliability")

/** One recorded proof-of-contribution. */
data class Contribution(
    val label: String,
    val category: String,
    val hp: Double,
    val ts: Long,
    val location: String = "",
)

/**
 * Proof-of-Contribution / Human Value Score — on-device.
 *
 * Honest scope: local, no decentralized ledger, no global proof-of-impact
 * network. It records contributions by category and grows both a total score
 * and per-category scores, so "value reflects what you contribute" is real and
 * multi-dimensional. Manual logging (the "AI understands action" and "photo
 * evidence verification" layers are future work).
 */
class Reputation(private val context: Context) {

    fun score(): Int = runCatching {
        runBlocking { context.reputationStore.data.first()[KEY_SCORE] } ?: 0
    }.getOrDefault(0)

    fun categoryScores(): Map<String, Int> = runCatching {
        val raw = runBlocking { context.reputationStore.data.first()[KEY_CATS] } ?: "{}"
        val o = JSONObject(raw)
        CONTRIBUTION_CATEGORIES.associateWith { o.optInt(it, 0) }
    }.getOrDefault(CONTRIBUTION_CATEGORIES.associateWith { 0 })

    fun contributions(): List<Contribution> = runCatching {
        val raw = runBlocking { context.reputationStore.data.first()[KEY_LOG] } ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Contribution(
                label = o.optString("label", "contribution"),
                category = o.optString("category", "community"),
                hp = o.optDouble("hp", 0.0),
                ts = o.optLong("ts", System.currentTimeMillis()),
                location = o.optString("location", ""),
            )
        }
    }.getOrDefault(emptyList())

    /** Record a contribution under a category; grows total + category score. */
    suspend fun addContribution(label: String, category: String, hp: Double, location: String = "") {
        val cat = if (category in CONTRIBUTION_CATEGORIES) category else "community"
        val total = (runBlocking { context.reputationStore.data.first()[KEY_SCORE] } ?: 0)
        val bump = (1 + (hp / 10.0).toInt()).coerceIn(1, 50)
        val cats = categoryScores().toMutableMap()
        cats[cat] = (cats[cat] ?: 0) + bump
        val log = JSONArray()
        (contributions() + Contribution(label, cat, hp, System.currentTimeMillis(), location)).takeLast(100).forEach {
            log.put(JSONObject()
                .put("label", it.label).put("category", it.category).put("hp", it.hp)
                .put("ts", it.ts).put("location", it.location))
        }
        val catsJson = JSONObject()
        cats.forEach { (k, v) -> catsJson.put(k, v) }
        runBlocking {
            context.reputationStore.edit { p ->
                p[KEY_SCORE] = (total + bump).coerceAtMost(999)
                p[KEY_LOG] = log.toString()
                p[KEY_CATS] = catsJson.toString()
            }
        }
    }

    /** A short, human summary Mr Happy can read out loud. */
    fun summary(): String {
        val total = score()
        val cats = categoryScores()
        val top = cats.maxByOrNull { it.value }?.let { "${it.key} ${it.value}pt" } ?: "none yet"
        val n = contributions().size
        return "Human Value Score: $total/999 across ${n} contribution${if (n == 1) "" else "s"}. " +
            "Top area: $top. " +
            (if (n == 0) "Log a contribution in your wallet to begin." else "Keep contributing to grow it.")
    }

    /**
     * Recommend the highest-impact actions for this person, based on their
     * strongest category (build on strengths) and their weakest (round out).
     * Honest heuristics, no model.
     */
    fun suggestMissions(): List<String> {
        val cats = categoryScores()
        val top = cats.maxByOrNull { it.value }?.key ?: "community"
        val low = cats.minByOrNull { it.value }?.key
        val missions = mapOf(
            "knowledge" to listOf(
                "Explain a topic you know well to a friend",
                "Teach a small skill to someone new",
                "Share a genuinely useful tip",
            ),
            "creativity" to listOf(
                "Create something original (art / music / code / writing)",
                "Make a small design or prototype",
                "Record a short creative piece",
            ),
            "community" to listOf(
                "Help someone with a real task",
                "Support a local group or neighbour",
                "Recommend a useful resource to someone",
            ),
            "environmental" to listOf(
                "Plant or care for a plant",
                "Do a small cleanup",
                "Cut waste for a day",
            ),
            "reliability" to listOf(
                "Follow through on a commitment",
                "Help someone consistently for a week",
                "Volunteer reliably for one session",
            ),
        )
        val base = missions[top] ?: missions.getValue("community")
        val builds = base.take(2)
        val rounds = low?.takeIf { it != top }?.let { missions[it]?.first() }
        return (builds + listOfNotNull(rounds)).filter { it.isNotBlank() }.take(3)
    }

    /** Best-effort last known location ("" if permission not granted). */
    fun lastKnownLocation(): String = runCatching {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return@runCatching ""
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
        val loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            ?: return@runCatching ""
        "%.4f,%.4f".format(loc.latitude, loc.longitude)
    }.getOrDefault("")

    /** A verifiable proof report for the most recent contribution. */
    fun report(): String {
        val c = contributions().lastOrNull() ?: return "No contributions logged yet."
        val whenStr = java.text.SimpleDateFormat("EEE, d MMM yyyy h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(c.ts))
        val loc = if (c.location.isNotBlank()) " at ${c.location}" else ""
        return "Proof of contribution:\n• ${c.label} (${c.category})\n• $whenStr$loc\n• +${"%.0f".format(c.hp)} HP"
    }

    /** Portable contribution identity — the "Happy Passport". */
    fun passport(name: String = ""): String {
        val c = categoryScores()
        val total = score()
        val n = contributions().size
        val top = c.entries.sortedByDescending { it.value }.take(2)
            .joinToString(" / ") { "${it.key} ${it.value}pt" }
        val who = if (name.isNotBlank()) name else "Me"
        val tier = when {
            total >= 800 -> "Gold Contributor"
            total >= 500 -> "Silver Contributor"
            total >= 200 -> "Bronze Contributor"
            else -> "Emerging Contributor"
        }
        return buildString {
            appendLine("🪪 Happy Passport — $who")
            appendLine("Tier: $tier")
            appendLine("Human Value Score: $total/999")
            appendLine("Contributions: $n")
            appendLine("Top areas: $top")
            if (n == 0) appendLine("Log a contribution in your wallet to build it.")
        }.trim()
    }
}
