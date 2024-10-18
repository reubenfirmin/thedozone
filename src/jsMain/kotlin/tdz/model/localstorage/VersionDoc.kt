package tdz.model.localstorage

import kotlin.js.Json
import kotlin.js.json

data class VersionDoc(val major: Int,
                      val minor: Int,
                      val patch: Int): HasKey {

    override val key: String
        get() = singletonKey()

    override fun toJson() = json(
        "major" to major,
        "minor" to minor,
        "patch" to patch)

    companion object {
        fun singletonKey() = "version"

        fun fromJson(json: Json) = VersionDoc(
            json["major"] as Int,
            json["minor"] as Int,
            json["patch"] as Int
        )
    }
}

