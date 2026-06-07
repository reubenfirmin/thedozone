package tdz.model.localstorage

// namespace for storing data - keep consistent otherwise storage will need migration
enum class Namespace {
    NEWCANVAS,
    COLUMN,
    COLUMN_MODE,
    VERSION,
    DROPBOX_CONFIG,
    SYNC_STATE,
    THEME
}