package me.whish.emotify.client

import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.settings.ClientConfigurationVersion

object NeoForgeClientConfigVersionCodec {
    fun inspect(source: String): ClientConfigurationVersion {
        var declaredVersion: Int? = null
        var observed = false
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            if (separator <= 0 || line.substring(0, separator).trim() != CONFIG_VERSION_KEY) {
                return@forEachIndexed
            }
            require(!observed) { "Duplicate Emotify client config version at line ${index + 1}" }
            val value = line.substring(separator + 1).substringBefore('#').trim()
            declaredVersion = requireNotNull(value.toIntOrNull()) {
                "Invalid Emotify client config version at line ${index + 1}: $value"
            }
            observed = true
        }
        return ClientConfigurationSchema.classify(declaredVersion)
    }

    private const val CONFIG_VERSION_KEY = "configVersion"
}
