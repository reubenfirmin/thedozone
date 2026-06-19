package tdz.sync

import js.typedarrays.Uint8Array
import kotlinx.browser.localStorage
import kotlinx.coroutines.await
import web.crypto.crypto
import web.events.EventType
import web.events.addEventListener
import web.events.removeEventListener
import web.messaging.MessageEvent
import web.timers.clearInterval
import web.timers.setInterval
import web.window.window
import kotlin.js.Promise
import kotlin.js.json

/** Shape of the message `oauth.html` posts back to the opener. */
private external interface OauthMessage {
    val source: String?
    val error: String?
    val state: String?
    val code: String?
}

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
        val verifier = auth.getCodeVerifier()
        localStorage.setItem(VERIFIER_KEY, verifier)

        // Clear the persisted verifier no matter what: if the popup is closed or errors, a stale
        // verifier left behind would be reused by the next connect attempt and break PKCE.
        val code = try {
            awaitPopupCode(url, state)
        } finally {
            localStorage.removeItem(VERIFIER_KEY)
        }

        auth.setCodeVerifier(verifier)

        val tokenResult = auth.getAccessTokenFromCode(redirectUri(), code).await()
        // Validate the untyped SDK response instead of blind-casting: an unexpected shape (no refresh
        // token) fails loudly here at the interop boundary, not as a null-as-String corruption later.
        val refreshToken = tokenResult.result.refresh_token as? String
            ?: error("Dropbox token exchange returned no refresh_token")

        // Best-effort display email: needs account_info.read, which we don't require. If it's not
        // granted (or the call fails), connect still succeeds with a generic label.
        val email = runCatching {
            val client = DropboxSdk(json("clientId" to DropboxApp.KEY, "refreshToken" to refreshToken))
            client.usersGetCurrentAccount().await().result.email as? String
                ?: error("Dropbox account response missing email")
        }.getOrDefault("Dropbox account")

        return DropboxCredentials(refreshToken, email)
    }

    /** 16 cryptographically-random bytes as lowercase hex (the OAuth CSRF `state` value). */
    private fun randomState(): String {
        val bytes = crypto.getRandomValues(Uint8Array(16))
        return buildString {
            for (i in 0 until bytes.length) {
                append((bytes[i].toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    /** Open the auth popup and resolve with the `code` from its postMessage. */
    private suspend fun awaitPopupCode(url: String, expectedState: String): String =
        Promise<String> { resolve, reject ->
            // kotlinx.browser's open(): typed, takes plain strings, and its Window exposes `closed`.
            val popup = kotlinx.browser.window.open(url, "tdz-dropbox", "width=560,height=720")
            if (popup == null) {
                reject(Exception("Popup blocked — allow popups for this site and retry."))
                return@Promise
            }
            var handler: ((MessageEvent<OauthMessage?>) -> Unit)? = null
            var closedPoll: web.timers.Interval? = null
            fun cleanup() {
                handler?.let { window.removeEventListener(EventType("message"), it) }
                closedPoll?.let { clearInterval(it) }
            }
            handler = { event ->
                val msg = event.data
                if (msg != null && msg.source == "tdz-dropbox-oauth") {
                    cleanup()
                    val code = msg.code
                    when {
                        msg.error != null -> reject(Exception("Dropbox: ${msg.error}"))
                        msg.state != expectedState -> reject(Exception("OAuth state mismatch"))
                        code != null -> resolve(code)
                        else -> reject(Exception("No authorization code returned"))
                    }
                }
            }
            window.addEventListener(EventType("message"), handler)
            // If the user closes the popup without authorizing, no message ever arrives. Poll for it
            // so connect() doesn't hang forever in the locked Connecting state (blocking overlay up).
            closedPoll = setInterval({
                if (popup.closed) {
                    cleanup()
                    reject(Exception("Dropbox authorization was cancelled."))
                }
            }, 500)
        }.await()
}
