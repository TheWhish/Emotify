package me.whish.emotify.server.core

sealed interface ServerConfigurationVersion {
    data object Legacy : ServerConfigurationVersion

    data object Current : ServerConfigurationVersion

    data class Future(val value: Int) : ServerConfigurationVersion {
        init {
            require(value > ServerConfigurationSchema.CURRENT_VERSION) {
                "Future server configuration version must exceed ${ServerConfigurationSchema.CURRENT_VERSION}: $value"
            }
        }
    }
}

object ServerConfigurationSchema {
    const val LEGACY_VERSION = 0
    const val CURRENT_VERSION = 1

    fun classify(declaredVersion: Int?): ServerConfigurationVersion {
        val version = declaredVersion ?: LEGACY_VERSION
        require(version >= LEGACY_VERSION) { "Server configuration version must not be negative: $version" }
        return when {
            version == LEGACY_VERSION -> ServerConfigurationVersion.Legacy
            version == CURRENT_VERSION -> ServerConfigurationVersion.Current
            else -> ServerConfigurationVersion.Future(version)
        }
    }
}
