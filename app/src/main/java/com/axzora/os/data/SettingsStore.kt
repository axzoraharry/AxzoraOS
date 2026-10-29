package com.axzora.os.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "axzora_settings")

/** Immutable snapshot of all user-customisable settings. */
data class UserSettings(
    val darkMode: Boolean = true,
    val accentIndex: Int = 0,
    val gridColumns: Int = 4,
    val displayName: String = "",
    val locationSharing: Boolean = false,
    val familyCode: String = "",
    val serverUrl: String = "",
    val deviceId: String = "",
    /** Override for the on-device model download (blank = the built-in default). */
    val modelUrl: String = "",
    /** HuggingFace access token, to fetch gated models like Gemma. */
    val hfToken: String = "",
    val localBrainUrl: String = "http://192.168.29.250:11434",
    val onDeviceBrainUrl: String = "http://127.0.0.1:9095",
    val ludoFirebaseUrl: String = "",
    val adminAgentUrl: String = "http://192.168.29.250:11434",
    /** Call relay (TURN) the admin configured; re-pushed to the server on every admin open. */
    val turnUrl: String = "",
    val turnUser: String = "",
    val turnPass: String = "",
    val hiddenApps: Set<String> = emptySet(),
    val favoriteApps: List<String> = emptyList(),
    val onboarded: Boolean = false,
    val phoneNumber: String = "",
    val currencyCode: String = "",
    val trustedPeers: Set<String> = emptySet(),
    val bubbleEnabled: Boolean = false,
    /** Which revision of the permission list the user has been shown. */
    val permsVersion: Int = 0,
    /** Index into [com.axzora.os.ui.ThemeStyles]. */
    val themeStyle: Int = 0,
    /** 0 = no motion, 1 = essential only, 2 = everything. */
    val animLevel: Int = 2,
    /** App drawer as a grid of tiles rather than a list. */
    val drawerGrid: Boolean = true,
    /** 0 = A to Z, 1 = newest installs first. */
    val drawerSort: Int = 0,
    /** 0 = relaxed, 1 = normal, 2 = fast. Scales every animation's length. */
    val animSpeed: Int = 1,
    /** Which clock face the home screen wears — see ClockStyles. */
    val clockStyle: Int = 0,
    /** 0 small, 1 medium, 2 large. */
    val clockSize: Int = 1,
    /** Index into Atmospheres; 0 keeps the phone's own wallpaper. */
    val atmosphere: Int = 0,
)

/** Thin wrapper over Preferences DataStore exposing a typed [UserSettings] flow. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val DARK = booleanPreferencesKey("dark_mode")
        val ACCENT = intPreferencesKey("accent_index")
        val COLUMNS = intPreferencesKey("grid_columns")
        val NAME = stringPreferencesKey("display_name")
        val SHARING = booleanPreferencesKey("location_sharing")
        val FAMILY = stringPreferencesKey("family_code")
        val SERVER = stringPreferencesKey("server_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val MODEL_URL = stringPreferencesKey("model_url")
        val LOCAL_BRAIN_URL = stringPreferencesKey("local_brain_url")
        val ONDEVICE_BRAIN_URL = stringPreferencesKey("ondevice_brain_url")
        val LUDO_FB = stringPreferencesKey("ludo_firebase_url")
        val ADMIN_AGENT = stringPreferencesKey("admin_agent_url")
        val TURN_URL = stringPreferencesKey("turn_url")
        val TURN_USER = stringPreferencesKey("turn_user")
        val TURN_PASS = stringPreferencesKey("turn_pass")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val HIDDEN = stringSetPreferencesKey("hidden_apps")
        val FAVORITES = stringPreferencesKey("favorite_apps")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val PHONE = stringPreferencesKey("phone_number")
        val CURRENCY = stringPreferencesKey("currency_code")
        val TRUSTED = stringSetPreferencesKey("trusted_peers")
        val BUBBLE = booleanPreferencesKey("bubble_enabled")
        val PERMS = intPreferencesKey("perms_version")
        val STYLE = intPreferencesKey("theme_style")
        val ANIM = intPreferencesKey("anim_level")
        val DRAWER_GRID = booleanPreferencesKey("drawer_grid")
        val DRAWER_SORT = intPreferencesKey("drawer_sort")
        val ANIM_SPEED = intPreferencesKey("anim_speed")
        val CLOCK_STYLE = intPreferencesKey("clock_style")
        val CLOCK_SIZE = intPreferencesKey("clock_size")
        val ATMOSPHERE = intPreferencesKey("atmosphere")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            darkMode = p[Keys.DARK] ?: true,
            accentIndex = p[Keys.ACCENT] ?: 0,
            gridColumns = p[Keys.COLUMNS] ?: 4,
            displayName = p[Keys.NAME] ?: "",
            modelUrl = p[Keys.MODEL_URL] ?: "",
            hfToken = p[Keys.HF_TOKEN] ?: "",
            localBrainUrl = p[Keys.LOCAL_BRAIN_URL] ?: "",
            onDeviceBrainUrl = p[Keys.ONDEVICE_BRAIN_URL] ?: "http://127.0.0.1:9095",
            ludoFirebaseUrl = p[Keys.LUDO_FB] ?: "",
            adminAgentUrl = p[Keys.ADMIN_AGENT] ?: "http://192.168.29.250:11434",
            turnUrl = p[Keys.TURN_URL] ?: "",
            turnUser = p[Keys.TURN_USER] ?: "",
            turnPass = p[Keys.TURN_PASS] ?: "",
            locationSharing = p[Keys.SHARING] ?: false,
            familyCode = (p[Keys.FAMILY] ?: "").trim().uppercase(),
            // Falls back to the shipped backend so nobody has to enter a URL.
            serverUrl = (p[Keys.SERVER] ?: "").ifBlank { com.axzora.os.Config.DEFAULT_SERVER },
            deviceId = p[Keys.DEVICE_ID] ?: "",
            hiddenApps = p[Keys.HIDDEN] ?: emptySet(),
            favoriteApps = p[Keys.FAVORITES]?.split("|")?.filter { it.isNotBlank() } ?: emptyList(),
            onboarded = p[Keys.ONBOARDED] ?: false,
            phoneNumber = p[Keys.PHONE] ?: "",
            // Blank means "not chosen yet" — the UI falls back to the device locale.
            currencyCode = p[Keys.CURRENCY] ?: "",
            trustedPeers = p[Keys.TRUSTED] ?: emptySet(),
            bubbleEnabled = p[Keys.BUBBLE] ?: false,
            permsVersion = p[Keys.PERMS] ?: 0,
            themeStyle = p[Keys.STYLE] ?: 0,
            animLevel = p[Keys.ANIM] ?: 2,
            drawerGrid = p[Keys.DRAWER_GRID] ?: true,
            drawerSort = p[Keys.DRAWER_SORT] ?: 0,
            animSpeed = p[Keys.ANIM_SPEED] ?: 1,
            clockStyle = p[Keys.CLOCK_STYLE] ?: 0,
            clockSize = p[Keys.CLOCK_SIZE] ?: 1,
            atmosphere = p[Keys.ATMOSPHERE] ?: 0,
        )
    }

    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }

    suspend fun setPhoneNumber(v: String) = context.dataStore.edit { it[Keys.PHONE] = v.trim() }

    suspend fun setCurrency(code: String) = context.dataStore.edit { it[Keys.CURRENCY] = code }

    /** Nearby devices already paired with, so they reconnect without a tap. */
    suspend fun setTrustedPeers(names: Set<String>) =
        context.dataStore.edit { it[Keys.TRUSTED] = names }

    suspend fun setPermsVersion(v: Int) = context.dataStore.edit { it[Keys.PERMS] = v }

    suspend fun setThemeStyle(v: Int) = context.dataStore.edit { it[Keys.STYLE] = v }

    suspend fun setAnimLevel(v: Int) = context.dataStore.edit { it[Keys.ANIM] = v.coerceIn(0, 2) }

    suspend fun setDrawerGrid(v: Boolean) = context.dataStore.edit { it[Keys.DRAWER_GRID] = v }

    suspend fun setDrawerSort(v: Int) = context.dataStore.edit { it[Keys.DRAWER_SORT] = v.coerceIn(0, 1) }

    suspend fun setAnimSpeed(v: Int) = context.dataStore.edit { it[Keys.ANIM_SPEED] = v.coerceIn(0, 2) }

    suspend fun setClockStyle(v: Int) = context.dataStore.edit { it[Keys.CLOCK_STYLE] = v.coerceIn(0, 3) }

    suspend fun setClockSize(v: Int) = context.dataStore.edit { it[Keys.CLOCK_SIZE] = v.coerceIn(0, 2) }

    suspend fun setAtmosphere(v: Int) = context.dataStore.edit { it[Keys.ATMOSPHERE] = v }

    suspend fun setBubble(v: Boolean) = context.dataStore.edit { it[Keys.BUBBLE] = v }

    suspend fun setDarkMode(v: Boolean) = context.dataStore.edit { it[Keys.DARK] = v }
    suspend fun setAccent(i: Int) = context.dataStore.edit { it[Keys.ACCENT] = i }
    suspend fun setColumns(n: Int) = context.dataStore.edit { it[Keys.COLUMNS] = n.coerceIn(3, 6) }
    suspend fun setDisplayName(v: String) = context.dataStore.edit { it[Keys.NAME] = v }
    suspend fun setLocationSharing(v: Boolean) = context.dataStore.edit { it[Keys.SHARING] = v }
    /** Normalised so capitalisation can't split one family into two groups. */
    suspend fun setFamilyCode(v: String) =
        context.dataStore.edit { it[Keys.FAMILY] = v.trim().uppercase() }
    suspend fun setServerUrl(v: String) = context.dataStore.edit { it[Keys.SERVER] = v.trim() }
    suspend fun setModelUrl(v: String) = context.dataStore.edit { it[Keys.MODEL_URL] = v.trim() }
    suspend fun setLocalBrainUrl(v: String) = context.dataStore.edit { it[Keys.LOCAL_BRAIN_URL] = v.trim() }
    suspend fun setOnDeviceBrainUrl(v: String) = context.dataStore.edit { it[Keys.ONDEVICE_BRAIN_URL] = v.trim() }
    suspend fun setLudoFirebaseUrl(v: String) = context.dataStore.edit { it[Keys.LUDO_FB] = v.trim() }
    suspend fun setAdminAgentUrl(v: String) = context.dataStore.edit { it[Keys.ADMIN_AGENT] = v.trim() }
    suspend fun setTurn(url: String, user: String, pass: String) = context.dataStore.edit {
        it[Keys.TURN_URL] = url.trim(); it[Keys.TURN_USER] = user.trim(); it[Keys.TURN_PASS] = pass.trim()
    }
    suspend fun setHfToken(v: String) = context.dataStore.edit { it[Keys.HF_TOKEN] = v.trim() }

    /** Returns this device's stable random id, generating it on first use. */
    suspend fun ensureDeviceId(): String {
        var id = ""
        context.dataStore.edit { p ->
            id = p[Keys.DEVICE_ID] ?: java.util.UUID.randomUUID().toString().also { p[Keys.DEVICE_ID] = it }
        }
        return id
    }

    suspend fun toggleHidden(pkg: String) = context.dataStore.edit { p ->
        val cur = p[Keys.HIDDEN] ?: emptySet()
        p[Keys.HIDDEN] = if (pkg in cur) cur - pkg else cur + pkg
    }

    suspend fun toggleFavorite(pkg: String) = context.dataStore.edit { p ->
        val cur = (p[Keys.FAVORITES]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
        if (pkg in cur) cur.remove(pkg) else cur.add(pkg)
        p[Keys.FAVORITES] = cur.joinToString("|")
    }
}
