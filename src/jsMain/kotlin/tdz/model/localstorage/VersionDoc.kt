package tdz.model.localstorage

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString

@Serializable
data class VersionDoc(val major: Int,
                      val minor: Int,
                      val patch: Int): HasKey {

    @Transient
    override val key: String = singletonKey()

    override fun encode(): String = storageJson.encodeToString(this)

    companion object {
        fun singletonKey() = "version"

        fun decode(raw: String): VersionDoc = storageJson.decodeFromString(raw)
    }
}
