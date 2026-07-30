package me.whish.emotify.catalog.builtin

import me.whish.emotify.domain.EmotionId

object BuiltInEmotionManifest {
    const val SCHEMA_VERSION = 4

    private const val RESOURCE_PATH = "/assets/emotify/emotions.json"
    private val snapshot = BuiltInEmotionManifestLoader.loadResource(BuiltInEmotionManifest::class.java, RESOURCE_PATH)

    val source: BuiltInEmotionSource = snapshot.source
    val categories: List<BuiltInEmotionCategory> = snapshot.categories
    val defaultFavoriteIds: List<EmotionId> = snapshot.defaultFavoriteIds
    val definitions: List<BuiltInEmotionDefinition> = snapshot.definitions
    internal val catalog = snapshot.catalog

    fun find(id: EmotionId): BuiltInEmotionDefinition? = snapshot.find(id)

    fun findCategory(id: String): BuiltInEmotionCategory? = snapshot.findCategory(id)
}
