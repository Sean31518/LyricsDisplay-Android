package de.lyricsdisplay.app.engine

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify-OAuth als Public Client (PKCE, kein Client-Secret) - Port von
 * routes/auth.js + services/spotifyTokens.js. Jeder Nutzer trägt seine eigene
 * Spotify-Client-ID ein (siehe Onboarding-Screen); der Redirect landet über
 * einen Custom-URI-Scheme-Intent-Filter zurück in MainActivity.
 */
class SpotifyAuthManager(private val context: Context) {

    companion object {
        const val REDIRECT_URI = "de.lyricsdisplay.app://callback"
        private const val SCOPES = "user-read-currently-playing user-read-playback-state"
        private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val TIMEOUT_MS = 8000

        private const val PREFS_NAME = "spotify_auth_secure"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
    }

    data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    sealed class AuthResult {
        object Success : AuthResult()
        data class Failure(val reason: String) : AuthResult()
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_CLIENT_ID, value?.trim()).apply() }

    fun isConfigured(): Boolean = !clientId.isNullOrBlank()

    fun getTokens(): Tokens? {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return Tokens(access, refresh, expiresAt)
    }

    fun isAuthenticated(): Boolean = getTokens() != null

    fun logout() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    /**
     * Öffnet den Spotify-Login in einer Custom Tab (kein Embedded-WebView-Login).
     * launchContext muss ein Activity-Context sein (der gespeicherte [context]
     * ist der Application-Context - startActivity() von dort crasht ohne
     * FLAG_ACTIVITY_NEW_TASK, das CustomTabsIntent nicht automatisch setzt).
     */
    fun startLogin(launchContext: Context) {
        val id = clientId ?: return
        val verifier = randomUrlSafeString(64)
        val state = randomUrlSafeString(16)
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()

        val authorizeUri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .build()

        CustomTabsIntent.Builder().build().launchUrl(launchContext, authorizeUri)
    }

    /** Von MainActivity.onNewIntent bei Redirect auf REDIRECT_URI aufgerufen. */
    fun handleRedirect(uri: Uri): AuthResult {
        uri.getQueryParameter("error")?.let { return AuthResult.Failure(it) }

        val code = uri.getQueryParameter("code")
            ?: return AuthResult.Failure("missing_code")
        val state = uri.getQueryParameter("state")
        val pendingState = prefs.getString(KEY_PENDING_STATE, null)
        val verifier = prefs.getString(KEY_PENDING_VERIFIER, null)
        prefs.edit().remove(KEY_PENDING_STATE).remove(KEY_PENDING_VERIFIER).apply()

        if (state == null || state != pendingState) return AuthResult.Failure("state_mismatch")
        if (verifier == null) return AuthResult.Failure("missing_verifier")

        val id = clientId ?: return AuthResult.Failure("not_configured")
        return try {
            val (status, body) = httpPostForm(
                TOKEN_URL,
                listOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "client_id" to id,
                    "code_verifier" to verifier,
                ),
            )
            val json = body?.let { JSONObject(it) }
            if (status !in 200..299 || json == null || json.has("error")) {
                AuthResult.Failure(json?.optString("error_description", "token_exchange_failed") ?: "token_exchange_failed")
            } else {
                saveTokens(json)
                AuthResult.Success
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "auth_failed")
        }
    }

    /** Erneuert den Access-Token 60s vor Ablauf. Gibt false bei Fehler/Revoke zurück. */
    fun refreshIfNeeded(): Boolean {
        val tokens = getTokens() ?: return false
        if (System.currentTimeMillis() < tokens.expiresAt - 60_000) return true
        return refreshNow(tokens)
    }

    private fun refreshNow(tokens: Tokens): Boolean {
        val id = clientId ?: return false
        return try {
            val (status, body) = httpPostForm(
                TOKEN_URL,
                listOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to tokens.refreshToken,
                    "client_id" to id,
                ),
            )
            val json = body?.let { JSONObject(it) }
            if (status !in 200..299 || json == null || json.has("error")) {
                // Refresh-Token widerrufen - nicht bei jedem Poll-Tick neu versuchen
                if (json?.optString("error") == "invalid_grant") logout()
                false
            } else {
                // Spotify liefert nicht immer einen neuen refresh_token zurück
                saveTokens(json, fallbackRefreshToken = tokens.refreshToken)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun saveTokens(json: JSONObject, fallbackRefreshToken: String? = null) {
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token", "").ifEmpty { fallbackRefreshToken }
            ?: throw IllegalStateException("no refresh_token")
        val expiresIn = json.optLong("expires_in", 3600L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    private fun randomUrlSafeString(numBytes: Int): String {
        val bytes = ByteArray(numBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun httpPostForm(urlString: String, params: List<Pair<String, String>>): Pair<Int, String?> {
        val body = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URL(urlString).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            code to responseBody
        } finally {
            connection.disconnect()
        }
    }
}
