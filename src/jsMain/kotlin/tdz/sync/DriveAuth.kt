package tdz.sync

import zoned.framework.Debugger
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import tdz.model.localstorage.storageJson
import kotlin.js.Date

/** Result of a successful Drive connect: the account email for display (no persisted secret). */
data class DriveCredentials(val accountEmail: String)

/** Persisted shape of the short-lived access token (survives reloads while still valid). */
@Serializable
private data class StoredDriveToken(val accessToken: String, val expiresAtMs: Double)

/** The `about?fields=user` response — only the email is read. */
@Serializable
private data class DriveAbout(val user: DriveUser? = null)

@Serializable
private data class DriveUser(val emailAddress: String? = null)

/**
 * Google Drive auth over the GIS token model. Unlike Dropbox there is NO refresh token: access tokens
 * (~1h) cannot be silently re-minted reliably (GIS needs a live session + 3p cookies, often blocked),
 * so we PERSIST the access token + its expiry to localStorage and reuse it across page reloads while it
 * is still valid. This is what stops sync from "dying" minutes after a reload (especially under the
 * hot-reload dev server). The token is a short-lived (~1h) drive.file-scoped bearer token — less
 * sensitive than the Dropbox refresh token the app already stores.
 *
 * [connect] is interactive (consent popup). [token] returns a still-valid token (memory or restored
 * from storage), else attempts a silent mint; on any silent failure it throws
 * [NeedsInteractiveAuthException] so the controller surfaces a clean "Reconnect" prompt.
 */
object DriveAuth {

    private const val STORE_KEY = "tdz.sync.driveToken"
    private var accessToken: String? = null
    private var expiresAtMs: Double = 0.0
    private const val SKEW_MS = 60_000.0   // treat as expired a minute early to avoid mid-request expiry

    init { restore() }

    suspend fun connect(): DriveCredentials {
        DriveApi.awaitReady()
        val resp = DriveApi.requestToken(GoogleApp.CLIENT_ID, GoogleApp.SCOPE, prompt = "consent")
        cache(resp)
        Debugger.debug("[sync/auth] connected; token expires in ${resp.expiresInSec}s")
        // Email is available under drive.file via the about endpoint — best-effort, like Dropbox's.
        val email = runCatching { fetchAccountEmail(resp.accessToken) }.getOrNull() ?: "Google Drive account"
        return DriveCredentials(email)
    }

    /** A valid access token: cached (memory/storage) if live, else minted silently. */
    suspend fun token(): String {
        val cached = accessToken
        if (cached != null && Date.now() < expiresAtMs - SKEW_MS) {
            Debugger.debug("[sync/auth] token cache hit (${remainingSec()}s left)")
            return cached
        }
        if (cached == null) Debugger.debug("[sync/auth] no usable token in memory/storage (likely page reload); trying silent renewal")
        else Debugger.debug("[sync/auth] token expired/expiring (${remainingSec()}s); trying silent renewal")
        DriveApi.awaitReady()
        return try {
            val resp = DriveApi.requestToken(GoogleApp.CLIENT_ID, GoogleApp.SCOPE, prompt = "none")
            cache(resp)
            Debugger.debug("[sync/auth] silent renewal OK (expires in ${resp.expiresInSec}s)")
            resp.accessToken
        } catch (e: Throwable) {
            console.warn("[sync/auth] silent renewal failed: ${e.message}")
            throw NeedsInteractiveAuthException("Google session expired — click Reconnect to keep syncing.")
        }
    }

    fun revoke() {
        accessToken?.let { DriveApi.revoke(it) }
        accessToken = null
        expiresAtMs = 0.0
        runCatching { window.localStorage.removeItem(STORE_KEY) }
        Debugger.debug("[sync/auth] token revoked and cleared")
    }

    private fun remainingSec(): Int = ((expiresAtMs - Date.now()) / 1000.0).toInt()

    private fun cache(r: TokenResponse) {
        accessToken = r.accessToken
        expiresAtMs = Date.now() + r.expiresInSec * 1000.0
        runCatching {
            window.localStorage.setItem(
                STORE_KEY,
                storageJson.encodeToString(StoredDriveToken(r.accessToken, expiresAtMs)),
            )
        }
    }

    /** Restore a still-valid token persisted before a reload. */
    private fun restore() {
        runCatching {
            val raw = window.localStorage.getItem(STORE_KEY) ?: return
            val stored = storageJson.decodeFromString<StoredDriveToken>(raw)
            if (stored.accessToken.isNotEmpty() && Date.now() < stored.expiresAtMs - SKEW_MS) {
                accessToken = stored.accessToken
                expiresAtMs = stored.expiresAtMs
                Debugger.debug("[sync/auth] restored persisted Drive token (${remainingSec()}s left)")
            } else if (stored.accessToken.isNotEmpty()) {
                Debugger.debug("[sync/auth] persisted Drive token had expired")
            }
        }
    }

    private suspend fun fetchAccountEmail(token: String): String {
        val headers = Headers()
        headers.append("Authorization", "Bearer $token")
        val resp = window.fetch(
            "https://www.googleapis.com/drive/v3/about?fields=user",
            RequestInit(headers = headers),
        ).await()
        if (!resp.ok) throw Exception("about failed: ${resp.status}")
        val about = storageJson.decodeFromString<DriveAbout>(resp.text().await())
        return about.user?.emailAddress ?: throw Exception("about response had no user email")
    }
}
