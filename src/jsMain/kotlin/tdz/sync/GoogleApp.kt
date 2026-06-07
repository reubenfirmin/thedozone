package tdz.sync

/**
 * The application's public Google OAuth **Web application** client id, baked into the build. Like the
 * Dropbox app key this is SAFE to ship: it is public by design and, under the GIS token model, useless
 * without a user completing the consent popup — there is no client secret and no server.
 *
 * Registration (manual, see the design doc): create an OAuth 2.0 Web-application client in the Google
 * Cloud Console, enable the Drive API, and add the site origin(s) to "Authorized JavaScript origins"
 * (the GIS token model uses an internal popup — no redirect URI is required). Replace the placeholder
 * below with that client id.
 */
object GoogleApp {
    const val CLIENT_ID = "822021738562-n7mha2mdiu1rklb91t7f1jfnj1eu2v0o.apps.googleusercontent.com"

    /** drive.file: the app may only see/manage files IT creates; they stay visible in the user's Drive. */
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
}
