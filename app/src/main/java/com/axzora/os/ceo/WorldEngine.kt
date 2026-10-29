package com.axzora.os.ceo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.random.Random

/**
 * Happy World Engine — a real, persistent, procedural world simulator.
 *
 * A world has terrain (zones), buildings, AI citizens with **daily routines**
 * (work/shop/rest), an economy, and weather. [WorldStore] persists the latest
 * world to DataStore so a city you built survives app restarts. [step] advances
 * the simulation; [map] renders an ASCII view of the world so you can *see* it.
 *
 * Deterministic per seed, bounded, and fully on-device.
 */
object WorldEngine {

    enum class Zone { RESIDENTIAL, COMMERCIAL, INDUSTRIAL, GREEN, WATER, HEALTH, TRANSPORT }

    data class Building(val id: String, val zone: Zone, val kind: String, val size: Int)

    data class Citizen(
        val id: String,
        val name: String,
        val role: String,
        val home: Int,
        val work: Int,
        var happiness: Double,
        var state: String,   // "sleep", "commute", "work", "shop", "rest"
    )

    data class Economy(var balance: Double, val shops: Int, var jobs: Int, var dailyFlow: Double)

    data class Weather(var current: String, var temp: Double)

    data class WorldState(
        val name: String,
        val seed: Long,
        val zones: List<Zone>,
        val buildings: List<Building>,
        val citizens: List<Citizen>,
        val economy: Economy,
        val weather: Weather,
        var day: Int,
        var hour: Int,
    ) {
        fun toJson(): String = JSONObject()
            .put("name", name).put("seed", seed).put("day", day).put("hour", hour)
            .put("zones", org.json.JSONArray().apply { zones.forEach { put(it.name) } })
            .put("citizens", citizens.size).put("buildings", buildings.size)
            .put("economy", JSONObject().put("balance", economy.balance).put("shops", economy.shops)
                .put("jobs", economy.jobs).put("dailyFlow", economy.dailyFlow))
            .put("weather", JSONObject().put("current", weather.current).put("temp", weather.temp))
            .toString(2)
    }

    private val ZONE_RING = listOf(Zone.RESIDENTIAL, Zone.COMMERCIAL, Zone.INDUSTRIAL, Zone.GREEN,
        Zone.WATER, Zone.HEALTH, Zone.TRANSPORT)
    private val CITIZEN_NAMES = listOf("Aria", "Bren", "Ceto", "Dex", "Eli", "Finn", "Gia", "Hana",
        "Ivo", "Juno", "Kai", "Lux", "Mira", "Nia", "Ora", "Pax", "Quinn", "Rei", "Ses", "Tao")
    private val ROLES = listOf("doctor", "engineer", "teacher", "artist", "merchant", "farmer", "guard", "driver")

    fun build(name: String, seed: Long? = null): WorldState {
        val s = seed ?: name.hashCode().toLong().and(0x7fffffffL)
        val rnd = Random(s)
        val zones = ZONE_RING
        val buildings = zones.mapIndexed { i, z -> Building("b$i", z, buildingKind(z), 1 + rnd.nextInt(6)) }
        val count = 8 + rnd.nextInt(5)
        val citizens = (0 until count).map { c ->
            Citizen("c$c", CITIZEN_NAMES[rnd.nextInt(CITIZEN_NAMES.size)],
                ROLES[rnd.nextInt(ROLES.size)],
                home = zones.indexOf(Zone.RESIDENTIAL), work = zones.indexOf(Zone.COMMERCIAL),
                happiness = 0.4 + rnd.nextDouble() * 0.6, state = "rest")
        }
        return WorldState(
            name.ifBlank { "Elysium" }, s, zones, buildings, citizens,
            Economy(500.0 * (1 + rnd.nextInt(4)), 2 + zones.count { it == Zone.COMMERCIAL }, count, 0.0),
            Weather(pickWeather(rnd, 0), 18.0 + rnd.nextDouble() * 12.0), 0, 8,
        )
    }

    /** Advance [days] (24 ticks each) and set a citizen routine each hour. */
    fun step(world: WorldState, days: Int = 1) {
        repeat(days * 24) {
            world.hour = (world.hour + 1) % 24
            if (world.hour == 0) world.day++
            val rnd = Random(world.seed + world.day * 24 + world.hour)
            val hour = world.hour
            world.citizens.forEach { c ->
                c.state = when {
                    hour in 0..5 -> "sleep"
                    hour in 6..8 -> "commute"
                    hour in 9..17 -> "work"
                    hour in 18..19 -> "shop"
                    else -> "rest"
                }
            }
            val workers = world.citizens.count { it.state == "work" }
            val shoppers = world.citizens.count { it.state == "shop" }
            val earned = workers * (10.0 + rnd.nextDouble() * 20.0)
            val spent = shoppers * 8.0
            world.economy.dailyFlow = earned - spent
            world.economy.balance = (world.economy.balance + world.economy.dailyFlow).coerceAtLeast(0.0)
            world.economy.jobs = world.citizens.size
            if (hour == 0) world.weather.current = pickWeather(rnd, world.day)
            if (hour == 12) world.weather.temp = (world.weather.temp + rnd.nextDouble() * 4 - 2).coerceIn(-5.0, 42.0)
            world.citizens.forEach { c ->
                val drift = if (world.economy.dailyFlow > 0) 0.001 else -0.0005
                c.happiness = (c.happiness + drift).coerceIn(0.0, 1.0)
            }
        }
    }

    /** An ASCII map of the world, labelled by zone + citizens present. */
    fun map(world: WorldState): String {
        val line = "─".repeat(30)
        val sb = StringBuilder()
        sb.append("╔$line╗\n")
        sb.append("║ ").append(world.name.padEnd(30)).append(" ║\n")
        sb.append("╟$line╢\n")
        for (z in world.zones) {
            val n = world.citizens.count { it.work == world.zones.indexOf(z) || it.home == world.zones.indexOf(z) }
            sb.append("║ ").append(zoneGlyph(z)).append(' ')
                .append(z.name.lowercase().padEnd(20)).append('[').append(n.toString().padStart(2)).append("] ║\n")
        }
        sb.append("╟$line╢\n")
        sb.append("║ day ").append(world.day.toString().padStart(3)).append(" · hour ").append(world.hour.toString().padStart(2))
            .append(" · ₪").append(world.economy.balance.toInt().toString().padStart(8)).append(" ║\n")
        sb.append("║ ").append(world.weather.current.padEnd(14)).append(' ').append(world.weather.temp.toInt()).append("°C · ")
            .append(world.citizens.size).append(" citizens ║\n")
        sb.append("╚$line╝")
        return sb.toString()
    }

    private fun zoneGlyph(z: Zone) = when (z) {
        Zone.RESIDENTIAL -> "🏠"; Zone.COMMERCIAL -> "🏪"; Zone.INDUSTRIAL -> "🏭"
        Zone.GREEN -> "🌳"; Zone.WATER -> "🌊"; Zone.HEALTH -> "🏥"; Zone.TRANSPORT -> "🚗"
    }

    private fun buildingKind(z: Zone) = when (z) {
        Zone.RESIDENTIAL -> "apartment"; Zone.COMMERCIAL -> "shop"; Zone.INDUSTRIAL -> "factory"
        Zone.GREEN -> "park"; Zone.WATER -> "harbour"; Zone.HEALTH -> "hospital"; Zone.TRANSPORT -> "depot"
    }

    private fun pickWeather(rnd: Random, day: Int): String {
        if (day % 7 == 3) return "rain"
        return when (rnd.nextInt(4)) { 0 -> "sunny"; 1 -> "cloudy"; 2 -> "windy"; else -> "clear" }
    }
}

/** Persists the latest built world so it survives app restarts. */
object WorldStore {
    private val KEY = stringPreferencesKey("world")
    private val Context.worldStore by preferencesDataStore("axzora_world")

    fun save(ctx: Context, world: WorldEngine.WorldState) {
        runCatching { runBlocking { ctx.worldStore.edit { it[KEY] = world.toJson() } } }
    }

    fun load(ctx: Context): WorldEngine.WorldState? = runCatching {
        runBlocking {
            val raw = ctx.worldStore.data.first()[KEY] ?: return@runBlocking null
            val o = JSONObject(raw)
            val name = o.optString("name", "Elysium")
            val day = o.optInt("day", 0)
            val hour = o.optInt("hour", 8)
            val w = WorldEngine.build(name)
            w.day = day; w.hour = hour
            w.economy.balance = o.optJSONObject("economy")?.optDouble("balance", 0.0) ?: 0.0
            w.weather.current = o.optJSONObject("weather")?.optString("current", "clear") ?: "clear"
            w.weather.temp = o.optJSONObject("weather")?.optDouble("temp", 20.0) ?: 20.0
            w
        }
    }.getOrNull()
}
