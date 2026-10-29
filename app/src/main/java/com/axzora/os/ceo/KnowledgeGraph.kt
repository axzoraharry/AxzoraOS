package com.axzora.os.ceo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The CEO's shared world memory — a lightweight knowledge graph.
 *
 * Entities (people, places, businesses, projects, decisions) and typed relations
 * between them persist on-device. Every agent reads and writes it, so "create a
 * cafe campaign" remembers the cafe, its customers, and the budget decision next
 * week — the substrate of a real, continuous intelligence rather than a chat.
 *
 * Bounded ([MAX_ENTITIES] / [MAX_LINKS]); safe (only local DataStore); nothing
 * leaves the phone. This is the on-device stand-in for the vector DB + knowledge
 * graph in the server roadmap.
 */
object KnowledgeGraph {

    data class Entity(val id: String, val kind: String, val name: String, val props: Map<String, String>)

    private val KEY_E = stringPreferencesKey("entities")
    private val KEY_L = stringPreferencesKey("links")
    private const val MAX_ENTITIES = 200
    private const val MAX_LINKS = 300

    private val Context.kgStore by preferencesDataStore("axzora_kg")

    // ── entities ────────────────────────────────────────────────────────────

    fun addEntity(ctx: Context, kind: String, name: String, props: Map<String, String> = emptyMap()): String {
        val id = "$kind-${name.hashCode().and(0x7fffffff)}"
        runCatching {
            runBlocking {
                ctx.kgStore.edit { p ->
                    val arr = readArr(p[KEY_E])
                    arr.put(JSONObject().put("id", id).put("kind", kind).put("name", name)
                        .put("props", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } }))
                    p[KEY_E] = trim(arr, MAX_ENTITIES).toString()
                }
            }
        }
        return id
    }

    fun entities(ctx: Context): List<Entity> = runCatching {
        runBlocking {
            val arr = readArr(ctx.kgStore.data.first()[KEY_E])
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val props = mutableMapOf<String, String>()
                o.optJSONObject("props")?.keys()?.forEach { k -> props[k] = o.optJSONObject("props").optString(k) }
                Entity(o.optString("id"), o.optString("kind"), o.optString("name"), props)
            }
        }
    }.getOrDefault(emptyList())

    fun findEntity(ctx: Context, name: String): Entity? =
        entities(ctx).firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun countEntities(ctx: Context): Int = entities(ctx).size

    // ── links (typed relations) ─────────────────────────────────────────────

    fun addLink(ctx: Context, from: String, relation: String, to: String) {
        runCatching {
            runBlocking {
                ctx.kgStore.edit { p ->
                    val arr = readArr(p[KEY_L])
                    arr.put(JSONObject().put("from", from).put("rel", relation).put("to", to))
                    p[KEY_L] = trim(arr, MAX_LINKS).toString()
                }
            }
        }
    }

    fun links(ctx: Context): List<Triple<String, String, String>> = runCatching {
        runBlocking {
            val arr = readArr(ctx.kgStore.data.first()[KEY_L])
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(o.optString("from"), o.optString("rel"), o.optString("to"))
            }
        }
    }.getOrDefault(emptyList())

    fun countLinks(ctx: Context): Int = links(ctx).size

    /** A prompt-ready summary of the graph, for the CEO's system prompt. */
    fun summary(ctx: Context, max: Int = 20): String {
        val es = entities(ctx)
        if (es.isEmpty()) return ""
        val sb = StringBuilder("KNOWN WORLD (from the CEO's memory):\n")
        es.take(max).forEach { e ->
            sb.append("  • ${e.kind}: ${e.name}")
            if (e.props.isNotEmpty()) sb.append(" (" + e.props.entries.take(3).joinToString(", ") { "${it.key}=${it.value}" } + ")")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun clear(ctx: Context) {
        runCatching { runBlocking { ctx.kgStore.edit { it.clear() } } }
    }

    private fun readArr(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())

    private fun trim(arr: JSONArray, max: Int): JSONArray {
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - max) until arr.length()) out.put(arr.optJSONObject(i))
        return out
    }
}
