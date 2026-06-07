package tdz.sync

/**
 * The application's public Dropbox OAuth app key (client id), baked into the build. This is SAFE to
 * ship: it is public by design and, under PKCE, useless without the per-session code verifier — no
 * app secret exists and there is no server. Replace the placeholder with the key from the registered
 * Dropbox app (App Folder access). See Task 20 for registration steps.
 */
object DropboxApp {
    const val KEY = "s184u196uddft65"
}
