package tdz.sync

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlin.js.Promise
import kotlin.js.json

/** A minted access token and its lifetime (seconds), as returned by the GIS token client. */
data class TokenResponse(val accessToken: String, val expiresInSec: Int)

/** Raised when GIS cannot mint a token silently (no live Google session / consent needed). */
class NeedsInteractiveAuthException(message: String) : Exception(message)

/**
 * Low-level Google Identity Services (GIS) interop. The global `google.accounts.oauth2` is provided by
 * the `<script src="https://accounts.google.com/gsi/client">` tag in index.html (loaded async). All
 * dynamic access to GIS is confined here; [DriveAuth] adds the caching/policy on top. The GIS token
 * model is used (no redirect URI), which never yields a refresh token — see the design doc.
 */
object DriveApi {

    private fun oauth2(): dynamic = window.asDynamic().google?.accounts?.oauth2

    /** Suspend until the async GIS script has defined `google.accounts.oauth2` (bounded wait). */
    suspend fun awaitReady() {
        var waited = 0
        while (oauth2() == null || oauth2() == undefined) {
            if (waited >= 10_000) throw Exception("Google sign-in library failed to load.")
            delay(100)
            waited += 100
        }
    }

    /**
     * Request an access token via a one-shot token client. [prompt] is "" (default), "none" (silent —
     * fails if interaction is required) or "consent" (force the consent screen). A `none` request that
     * needs interaction surfaces as [NeedsInteractiveAuthException].
     */
    suspend fun requestToken(clientId: String, scope: String, prompt: String): TokenResponse =
        Promise<TokenResponse> { resolve, reject ->
            val client = oauth2().initTokenClient(
                json(
                    "client_id" to clientId,
                    "scope" to scope,
                    "callback" to { resp: dynamic ->
                        // Success is signalled by the presence of an access token — not the absence of
                        // an `error` field (which may be undefined in several non-success shapes too).
                        val accessToken = resp.access_token
                        if (accessToken != null && accessToken != undefined) {
                            resolve(TokenResponse(accessToken as String, (resp.expires_in as Number).toInt()))
                        } else {
                            val err = str(resp.error)
                            // GIS reports a failed silent attempt via these error codes.
                            if (err == "interaction_required" || err == "login_required" || err == "consent_required") {
                                reject(NeedsInteractiveAuthException("Google requires sign-in. Reconnect."))
                            } else {
                                val detail = str(resp.error_description)?.let { ": $it" } ?: ""
                                reject(Exception("Google auth error: ${err ?: "no access token returned"}$detail"))
                            }
                        }
                    },
                    "error_callback" to { e: dynamic ->
                        // GIS surfaces popup/network failures here as a GoogleOAuthError {type, message}.
                        val type = str(e?.type) ?: "unknown"
                        val msg = str(e?.message)?.let { ": $it" } ?: ""
                        when (type) {
                            "popup_closed", "popup_failed_to_open" ->
                                reject(Exception("Google authorization was cancelled or the popup was blocked$msg"))
                            else -> reject(NeedsInteractiveAuthException("Google sign-in needed ($type)$msg."))
                        }
                    },
                )
            )
            client.requestAccessToken(json("prompt" to prompt))
        }.await()

    /** Best-effort de-authorization so a later Connect re-prompts for consent. */
    fun revoke(accessToken: String) {
        runCatching { oauth2()?.revoke(accessToken) }
    }

    /** Coerce a dynamic JS value to a String?, treating both `null` and `undefined` as absent. */
    private fun str(v: dynamic): String? = if (v == null || v == undefined) null else v.toString()
}
