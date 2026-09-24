package uk.xa0.dsh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Everything the app needs to reach one DSH host.
 *
 * [manualCookie] exists because a DSH host can also be reached *directly* on the
 * carrier port, where the loopback-bound `dsh-auth-*` cookie is the only way in
 * (the LAN door normally mints its own session through /auth/login instead).
 */
data class DshConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val manualCookie: String = "",
    val themeMode: String = ThemeMode.SYSTEM,
    /** Agent preset used for newly created sessions; blank means "host default". */
    val agentPreset: String = "",
    /**
     * What the send button does while the agent is running: `queue` (deliver
     * after the current turn) or `steer` (interrupt the turn with the message).
     *
     * The web client keeps this in the host user-settings document, but a
     * remote browser is handed a `memory` settings scope whose writes are
     * dropped, so there is nothing host-side to read or write. It is a
     * client-local preference here, matching the web's `busyEnter` field and
     * its `'queue'` default.
     */
    val busyEnter: String = BusyEnter.QUEUE,
    /**
     * Sidebar Group mode: Workspaces (`true`) or one flat list. A client-local
     * preference like [busyEnter] — the host has no settings scope a phone can
     * write, and the sidebar's shape is a property of this client anyway.
     */
    val drawerGroupByWorkspace: Boolean = true,
    /** Sidebar Order mode: last updated (`true`) or the Workspace's manual order. */
    val drawerOrderByUpdated: Boolean = false,
    /**
     * The drawer's Archived filter. Saved for the same reason Group and Order are:
     * it is a view the user chose, and losing it on every restart is the bug the
     * user reported for Order ("on app restart the drawer's order should persist").
     */
    val drawerShowArchived: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** True when the user opted into pasting a raw cookie instead of logging in. */
    val usesManualCookie: Boolean get() = manualCookie.isNotBlank()
}

object ThemeMode {
    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"
    val all = listOf(SYSTEM, DARK, LIGHT)
}

/** Accepted `busyEnter` values, mirroring the host's `BUSY_ENTER_BEHAVIORS`. */
object BusyEnter {
    const val QUEUE = "queue"
    const val STEER = "steer"

    /** The mode the other gesture uses; the web maps Cmd/Ctrl+Enter to this. */
    fun flip(mode: String): String = if (mode == STEER) QUEUE else STEER
}

/**
 * Credentials live in EncryptedSharedPreferences (AES-256-GCM, key in the Android
 * Keystore) rather than plain prefs, because the password is kept so the app can
 * silently re-login when a session token expires (the auth proxy's default TTL
 * is 24h).
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "dsh_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): DshConfig = DshConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        manualCookie = prefs.getString(KEY_COOKIE, "").orEmpty(),
        themeMode = prefs.getString(KEY_THEME, ThemeMode.SYSTEM) ?: ThemeMode.SYSTEM,
        agentPreset = prefs.getString(KEY_PRESET, "").orEmpty(),
        busyEnter = prefs.getString(KEY_BUSY_ENTER, BusyEnter.QUEUE) ?: BusyEnter.QUEUE,
        drawerGroupByWorkspace = prefs.getBoolean(KEY_DRAWER_GROUP, true),
        drawerOrderByUpdated = prefs.getBoolean(KEY_DRAWER_ORDER, false),
        drawerShowArchived = prefs.getBoolean(KEY_DRAWER_ARCHIVED, false),
    )

    fun save(config: DshConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_COOKIE, config.manualCookie)
            .putString(KEY_THEME, config.themeMode)
            .putString(KEY_PRESET, config.agentPreset)
            .putString(KEY_BUSY_ENTER, config.busyEnter)
            .putBoolean(KEY_DRAWER_GROUP, config.drawerGroupByWorkspace)
            .putBoolean(KEY_DRAWER_ORDER, config.drawerOrderByUpdated)
            .putBoolean(KEY_DRAWER_ARCHIVED, config.drawerShowArchived)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_PASSWORD).remove(KEY_COOKIE).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_COOKIE = "cookie"
        const val KEY_THEME = "theme"
        const val KEY_PRESET = "preset"
        const val KEY_BUSY_ENTER = "busy_enter"
        const val KEY_DRAWER_GROUP = "drawer_group_by_workspace"
        const val KEY_DRAWER_ORDER = "drawer_order_by_updated"
        const val KEY_DRAWER_ARCHIVED = "drawer_show_archived"
    }
}
