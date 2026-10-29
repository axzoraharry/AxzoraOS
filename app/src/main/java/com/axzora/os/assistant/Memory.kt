package com.axzora.os.assistant

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.memoryStore by preferencesDataStore("axzora_memory")
private val KEY_FACTS = stringPreferencesKey("facts")
private val KEY_LOG = stringPreferencesKey("recent")
private val KEY_TAUGHT = stringPreferencesKey("taught")

/** Something the user told the assistant to keep. */
data class Fact(val subject: String, val value: String, val ts: Long)

/**
 * What the assistant remembers between conversations.
 *
 * Everything is stored on the phone in the app's private storage — nothing is
 * uploaded, which is the whole point of an assistant that runs offline. Facts
 * are keyed by subject so telling it a new value replaces the old one instead
 * of leaving two contradictory answers.
 */
class Memory(private val context: Context) {

    /** Reads are synchronous because the command engine is not a coroutine. */
    private fun readFacts(): MutableList<Fact> = runCatching {
        val raw = runBlocking { context.memoryStore.data.first()[KEY_FACTS] } ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fact(o.getString("s"), o.getString("v"), o.optLong("t"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun writeFacts(list: List<Fact>) {
        val arr = JSONArray()
        list.takeLast(200).forEach {
            arr.put(JSONObject().put("s", it.subject).put("v", it.value).put("t", it.ts))
        }
        runCatching { runBlocking { context.memoryStore.edit { p -> p[KEY_FACTS] = arr.toString() } } }
    }

    // ------------------------------------------------------------ teaching

    /**
     * Answers the user has taught the assistant directly.
     *
     * This is the "training" a phone can honestly do: no weights change, but
     * the assistant genuinely learns from you and keeps it forever. Stored
     * apart from facts because a taught answer is matched by the *shape* of a
     * question, not by a subject key.
     */
    private fun readTaught(): MutableList<Pair<String, String>> = runCatching {
        val raw = runBlocking { context.memoryStore.data.first()[KEY_TAUGHT] } ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("q") to o.getString("a")
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun teach(question: String, answer: String) {
        val q = normalize(question)
        if (q.isBlank() || answer.isBlank()) return
        val list = readTaught()
        list.removeAll { it.first == q }
        list.add(q to answer.trim())
        val arr = JSONArray()
        list.takeLast(300).forEach { arr.put(JSONObject().put("q", it.first).put("a", it.second)) }
        runCatching { runBlocking { context.memoryStore.edit { p -> p[KEY_TAUGHT] = arr.toString() } } }
    }

    /**
     * Finds a taught answer.
     *
     * Exact match first, then a generous word-overlap so "how do I reset the
     * router" still finds an answer taught as "how to reset router". Below
     * 60% shared words it declines rather than answering the wrong question.
     */
    fun taught(question: String): String? {
        val q = normalize(question)
        if (q.isBlank()) return null
        val list = readTaught()
        list.firstOrNull { it.first == q }?.let { return it.second }

        val words = meaningful(q)
        if (words.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = 0.0
        list.forEach { (storedQ, a) ->
            val sw = meaningful(storedQ)
            if (sw.isEmpty()) return@forEach
            val overlap = words.intersect(sw).size.toDouble() / maxOf(words.size, sw.size)
            if (overlap > bestScore) { bestScore = overlap; best = storedQ to a }
        }
        return if (bestScore >= 0.6) best?.second else null
    }

    fun taughtCount(): Int = readTaught().size

    fun forgetTaught(question: String): Boolean {
        val q = normalize(question)
        val list = readTaught()
        val before = list.size
        list.removeAll { it.first == q || it.first.contains(q) }
        if (list.size == before) return false
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("q", it.first).put("a", it.second)) }
        runCatching { runBlocking { context.memoryStore.edit { p -> p[KEY_TAUGHT] = arr.toString() } } }
        return true
    }

    /**
     * The words that carry the question's meaning.
     *
     * Question filler has to go before comparing, or phrasing defeats the
     * match: "shop timing" against "what is the shop timing" shares both of
     * its real words, but counting "what", "is" and "the" drags the score
     * under the threshold and the taught answer is missed.
     */
    private fun meaningful(s: String): Set<String> {
        val stop = setOf(
            "what", "whats", "the", "is", "are", "was", "a", "an", "of", "to",
            "for", "do", "does", "did", "you", "your", "me", "my", "i", "can",
            "could", "would", "please", "tell", "about", "and", "how", "when",
            "where", "who", "which", "any", "some", "it", "its", "this", "that",
        )
        return s.split(" ").filter { it.length > 1 && it !in stop }.toSet()
    }

    private fun normalize(s: String) = s.trim().lowercase()
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .replace(Regex("""\s+"""), " ")
        .trim()

    fun remember(subject: String, value: String) {
        val key = subject.trim().lowercase()
        val list = readFacts()
        list.removeAll { it.subject == key }
        list.add(Fact(key, value.trim(), System.currentTimeMillis()))
        writeFacts(list)
    }

    /** Looks up a fact, tolerating a loose phrasing of the subject. */
    fun recall(subject: String): String? {
        val q = subject.trim().lowercase().removePrefix("my ").removePrefix("the ")
        val list = readFacts()
        return list.firstOrNull { it.subject == q }?.value
            ?: list.firstOrNull { it.subject.contains(q) || q.contains(it.subject) }?.value
    }

    fun forget(subject: String): Boolean {
        val q = subject.trim().lowercase()
        val list = readFacts()
        val before = list.size
        list.removeAll { it.subject == q || it.subject.contains(q) }
        writeFacts(list)
        return list.size < before
    }

    fun all(): List<Fact> = readFacts().sortedByDescending { it.ts }

    fun clear() {
        runCatching { runBlocking { context.memoryStore.edit { it.remove(KEY_FACTS) } } }
    }

    // ---- short-term context ----

    /**
     * The last few things said, so a follow-up like "and tomorrow?" has
     * something to attach to. Kept small deliberately: this is conversational
     * context, not a transcript.
     */
    fun note(line: String) {
        runCatching {
            runBlocking {
                context.memoryStore.edit { p ->
                    val cur = (p[KEY_LOG] ?: "").split("\n").filter { it.isNotBlank() }
                    p[KEY_LOG] = (cur + line).takeLast(6).joinToString("\n")
                }
            }
        }
    }

    fun recent(): List<String> = runCatching {
        (runBlocking { context.memoryStore.data.first()[KEY_LOG] } ?: "")
            .split("\n").filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}
