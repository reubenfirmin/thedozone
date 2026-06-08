package tdz.sync

import kotlinx.browser.localStorage
import kotlinx.coroutines.await
import web.events.Event
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.window.window
import kotlin.js.Promise
import kotlin.js.json

/** Result of a successful connect: the long-lived refresh token + the account's email. */
data class DropboxCredentials(val refreshToken: String, val accountEmail: String)

object DropboxAuth {

    // Must cover every API we call: list_folder needs files.metadata.read; delete_v2 needs
    // files.metadata.write; up/download need files.content.*. account_info.read (for the display
    // email) is intentionally NOT requested — many App-Folder apps don't enable it, and requesting a
    // scope the app lacks makes Dropbox reject the whole authorization. Email is fetched best-effort.
    private val SCOPES = arrayOf(
        "files.metadata.read",
        "files.metadata.write",
        "files.content.read",
        "files.content.write",
    )
    private const val VERIFIER_KEY = "tdz_dropbox_code_verifier"

    private fun redirectUri(): String = window.location.origin + "/oauth.html"

    /**
     * Run the full PKCE popup flow using the baked-in public app key. Suspends until the popup posts
     * a code (or the user closes it / an error occurs), then exchanges the code for a refresh token
     * and reads the email.
     */
    suspend fun connect(): DropboxCredentials {
        val auth = DropboxAuthSdk(json("clientId" to DropboxApp.KEY))
        val state = randomState()
        val url = auth.getAuthenticationUrl(
            redirectUri(), state, "code", "offline", SCOPES, "none", true,
        ).await()
        // PKCE verifier must survive into the code exchange; persist keyed by state.
        localStorage.setItem(VERIFIER_KEY, auth.getCodeVerifier())

        val code = awaitPopupCode(url, state)

        auth.setCodeVerifier(localStorage.getItem(VERIFIER_KEY) ?: "")
        localStorage.removeItem(VERIFIER_KEY)

        val tokenResult = auth.getAccessTokenFromCode(redirectUri(), code).await()
        val refreshToken = tokenResult.result.refresh_token as String

        // Best-effort display email: needs account_info.read, which we don't require. If it's not
        // granted (or the call fails), connect still succeeds with a generic label.
        val email = runCatching {
            val client = DropboxSdk(json("clientId" to DropboxApp.KEY, "refreshToken" to refreshToken))
            client.usersGetCurrentAccount().await().result.email as String
        }.getOrDefault("Dropbox account")

        return DropboxCredentials(refreshToken, email)
    }

    private fun randomState(): String {
        val hex = js("(function() { var arr = new Uint8Array(16); crypto.getRandomValues(arr); return Array.from(arr, function(b) { return b.toString(16).padStart(2, '0'); }).join(''); })()")
        return hex as String
    }

    /** Open the auth popup and resolve with the `code` from its postMessage. */
    private suspend fun awaitPopupCode(url: String, expectedState: String): String =
        Promise<String> { resolve, reject ->
            val popup = window.asDynamic().open(url, "tdz-dropbox", "width=560,height=720")
            if (popup == null || popup == undefined) {
                reject(Exception("Popup blocked — allow popups for this site and retry."))
                return@Promise
            }
            var handler: ((Event) -> Unit)? = null
            var closedPoll = 0
            fun cleanup() {
                handler?.let { window.removeEventListener(EventType("message"), it) }
                if (closedPoll != 0) window.asDynamic().clearInterval(closedPoll)
            }
            handler = { event ->
                val msg = event.asDynamic().data
                if (msg != null && msg.source == "tdz-dropbox-oauth") {
                    cleanup()
                    when {
                        msg.error != null -> reject(Exception("Dropbox: ${msg.error}"))
                        msg.state != expectedState -> reject(Exception("OAuth state mismatch"))
                        msg.code != null -> resolve(msg.code as String)
                        else -> reject(Exception("No authorization code returned"))
                    }
                }
            }
            window.addEventListener(EventType("message"), handler)
            // If the user closes the popup without authorizing, no message ever arrives. Poll for it
            // so connect() doesn't hang forever in the locked Connecting state (blocking overlay up).
            closedPoll = window.asDynamic().setInterval({
                if (popup.closed == true) {
                    cleanup()
                    reject(Exception("Dropbox authorization was cancelled."))
                }
            }, 500) as Int
        }.await()
}
