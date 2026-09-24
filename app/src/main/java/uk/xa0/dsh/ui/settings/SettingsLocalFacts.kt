package uk.xa0.dsh.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import uk.xa0.dsh.BuildConfig
import uk.xa0.dsh.DshApplication

/**
 * The client-local facts the Settings sheet needs that `UiState` does not carry.
 *
 * The sheet is handed a curated slice of the view model's state, and neither the
 * credential store nor the cookie jar is part of it — the view model has no field
 * for either. They are still this app's own state, and a phone has nowhere else
 * to read them, so the Connection block reads them here rather than printing a
 * dash over the sign-in that actually exists. The verifier's "Account —" is the
 * case in point: the Setup screen's carrier-port path asks the user to *leave the
 * account blank* and paste a cookie, so a blank username is a real state and the
 * mode beside it is the honest answer.
 *
 * Only the mode and the presence cross this boundary — never a password and never
 * a cookie value. A null means "could not be read" (no `DshApplication`, e.g. a
 * preview), and the row says so instead of guessing.
 */
data class SettingsLocalFacts(
    val versionName: String,
    /** `Manual cookie` / `Password` / `Session cookie` / `None`. */
    val signIn: String?,
    /** An auth cookie is in the jar for the configured host. */
    val sessionCookie: Boolean?,
)

/**
 * Read once per sheet: both sources are disk-backed (EncryptedSharedPreferences
 * and the cookie jar's own prefs), so re-reading them per recomposition would put
 * keystore crypto on the composition thread. The sheet is closed to sign out, so
 * nothing re-reads them behind an open sheet either.
 */
@Composable
fun rememberSettingsLocalFacts(): SettingsLocalFacts {
    val app = LocalContext.current.applicationContext as? DshApplication
    return remember(app) {
        val config = app?.let { runCatching { it.configStore.load() }.getOrNull() }
        // The jar is keyed by the client's normalized root, the same URL the
        // cookies were stored for; the raw config string may lack a scheme. Every
        // request this app makes goes to that one host, so whatever it would send
        // is the host's auth cookie — no name matching needed (a pasted cookie can
        // be called anything).
        val root = runCatching { app?.client?.baseUrl() }.getOrNull()
        val hasCookie = root != null && runCatching {
            app?.client?.cookieJar?.loadForRequest(root)?.isNotEmpty() == true
        }.getOrDefault(false)
        SettingsLocalFacts(
            versionName = BuildConfig.VERSION_NAME,
            signIn = when {
                config == null -> null
                config.usesManualCookie -> "Manual cookie"
                // Kept so the app can silently re-login when a session expires.
                config.password.isNotBlank() -> "Password"
                hasCookie -> "Session cookie"
                else -> "None"
            },
            // No configured host means no jar entry to look up, so the answer is
            // "not known" rather than "no cookie".
            sessionCookie = if (config == null || root == null) null else hasCookie,
        )
    }
}
