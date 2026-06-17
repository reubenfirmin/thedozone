package tdz.sync

/**
 * The application's public Dropbox OAuth app key (client id), baked into the build. This is SAFE to
 * ship: it is public by design and, under PKCE, useless without the per-session code verifier — no
 * app secret exists and there is no server. The key below is The Do Zone's own; to run your own
 * deployment, register a Dropbox app (App Folder access) and substitute its key here.
 */
object DropboxApp {
    const val KEY = "s184u196uddft65"
}
