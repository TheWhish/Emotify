package me.whish.emotify.fabric.config

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

data class FabricClientConfigSnapshot(
    val reducedMotion: Boolean,
    val favorites: List<EmotionId>,
)

object FabricClientConfigCodec {
    fun decode(source: String, defaultFavorites: List<EmotionId>): FabricClientConfigSnapshot {
        var reducedMotion = false
        var favorites = defaultFavorites
        val observedKeys = HashSet<String>(2)
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEachIndexed
            }
            val separator = line.indexOf('=')
            require(separator > 0) { "Invalid Emotify client config line ${index + 1}" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(observedKeys.add(key)) { "Duplicate Emotify client config key: $key" }
            when (key) {
                "reducedMotion" -> reducedMotion = value.toBooleanStrict()
                "favorites" -> favorites = decodeFavorites(value)
                else -> Unit
            }
        }
        return FabricClientConfigSnapshot(reducedMotion, java.util.List.copyOf(favorites))
    }

    fun encode(snapshot: FabricClientConfigSnapshot): String = buildString {
        append("reducedMotion=")
        append(snapshot.reducedMotion)
        append('\n')
        append("favorites=")
        append(snapshot.favorites.joinToString(",", transform = EmotionId::value))
        append('\n')
    }

    private fun decodeFavorites(value: String): List<EmotionId> {
        if (value.isEmpty()) {
            return emptyList()
        }
        return java.util.List.copyOf(
            value.splitToSequence(',')
                .map(String::trim)
                .onEach { entry -> require(entry.isNotEmpty()) { "Empty favorite emotion ID" } }
                .map { entry -> requireNotNull(EmotionId.parse(entry)) { "Invalid favorite emotion ID: $entry" } }
                .distinct()
                .take(EmotionCatalog.MAX_SIZE)
                .toList(),
        )
    }
}
