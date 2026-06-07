package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.json

/** Result of a successful Drive connect: the account email for display (no persisted secret). */
data class DriveCredentials(val accountEmail: String)

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
        console.log("[sync/auth] connected; token expires in ${resp.expiresInSec}s")
        // Email is available under drive.file via the about endpoint — best-effort, like Dropbox's.
        val email = runCatching { fetchAccountEmail(resp.accessToken) }.getOrNull() ?: "Google Drive account"
        return DriveCredentials(email)
    }

    /** A valid access token: cached (memory/storage) if live, else minted silently. */
    suspend fun token(): String {
        val cached = accessToken
        if (cached != null && Date.now() < expiresAtMs - SKEW_MS) {
            console.log("[sync/auth] token cache hit (${remainingSec()}s left)")
            return cached
        }
        if (cached == null) console.log("[sync/auth] no usable token in memory/storage (likely page reload); trying silent renewal")
        else console.log("[sync/auth] token expired/expiring (${remainingSec()}s); trying silent renewal")
        DriveApi.awaitReady()
        return try {
            val resp = DriveApi.requestToken(GoogleApp.CLIENT_ID, GoogleApp.SCOPE, prompt = "none")
            cache(resp)
            console.log("[sync/auth] silent renewal OK (expires in ${resp.expiresInSec}s)")
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
        console.log("[sync/auth] token revoked and cleared")
    }

    private fun remainingSec(): Int = ((expiresAtMs - Date.now()) / 1000.0).toInt()

    private fun cache(r: TokenResponse) {
        accessToken = r.accessToken
        expiresAtMs = Date.now() + r.expiresInSec * 1000.0
        runCatching {
            window.localStorage.setItem(STORE_KEY, JSON.stringify(json(
                "accessToken" to r.accessToken,
                "expiresAtMs" to expiresAtMs,
            )))
        }
    }

    /** Restore a still-valid token persisted before a reload. */
    private fun restore() {
        runCatching {
            val raw = window.localStorage.getItem(STORE_KEY) ?: return
            val obj = JSON.parse<dynamic>(raw)
            val tok = obj.accessToken as? String
            val exp = (obj.expiresAtMs as? Number)?.toDouble() ?: 0.0
            if (!tok.isNullOrEmpty() && Date.now() < exp - SKEW_MS) {
                accessToken = tok
                expiresAtMs = exp
                console.log("[sync/auth] restored persisted Drive token (${remainingSec()}s left)")
            } else if (!tok.isNullOrEmpty()) {
                console.log("[sync/auth] persisted Drive token had expired")
            }
        }
    }

    private suspend fun fetchAccountEmail(token: String): String {
        val init = js("({})")
        init.headers = js("({})")
        init.headers["Authorization"] = "Bearer $token"
        val resp = (window.asDynamic().fetch(
            "https://www.googleapis.com/drive/v3/about?fields=user", init
        ) as Promise<dynamic>).await()
        if (resp.ok != true) throw Exception("about failed: ${resp.status}")
        val body = (resp.json() as Promise<dynamic>).await()
        return body.user.emailAddress as String
    }
}
