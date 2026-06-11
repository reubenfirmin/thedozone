package tdz.model.localstorage

import kotlinx.serialization.json.Json

/**
 * The single Json configuration for everything this app persists (localStorage docs, the synced
 * canvas manifest, the .tdz file header). kotlinx.serialization is minification-safe — serial names
 * are compile-time constants — so the previous hand-rolled `kotlin.js.Json` layer (built to dodge
 * IR member-name minification) is unnecessary; decodes are now schema-checked with real errors
 * instead of downstream ClassCastExceptions.
 *
 * - [Json.ignoreUnknownKeys]: a newer build's extra fields never crash this one.
 * - explicitNulls off: absent and explicit-null are equivalent for every optional field here, and
 *   both legacy shapes (the old serializer wrote explicit nulls) decode identically.
 */
val storageJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** A document that owns its persisted string form (kotlinx.serialization under the hood). */
interface Persistable {
    fun encode(): String
}
