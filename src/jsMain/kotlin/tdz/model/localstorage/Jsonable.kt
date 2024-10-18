package tdz.model.localstorage

import kotlin.js.Json

/**
 * The IR compiler aggressively minifies member names. Avoid this by being fully in control of the json serialization.
 */
interface JsonAble {
    fun toJson(): Json
}
