package tdz.model.localstorage

// Namespaces prefix localStorage keys (no separator) — renaming a value orphans stored data,
// so once there are real users a rename needs a read-through migration.
enum class Namespace {
    NEWCANVAS,
    COLUMN,
    COLUMN_MODE,
    SYNC_CONFIG,
    SYNC_STATE,
    THEME
}