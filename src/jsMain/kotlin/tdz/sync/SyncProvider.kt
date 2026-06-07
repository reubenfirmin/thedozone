package tdz.sync

/** The remote backends the sync vertical can target. The user picks exactly one at a time. */
enum class SyncProvider(val displayName: String) {
    DROPBOX("Dropbox"),
    GOOGLE_DRIVE("Google Drive"),
    LOCAL_FOLDER("Local Folder"),
}
