package me.whish.emotify.domain

class EmotionCatalog private constructor(source: Collection<EmotionId>) {
    val ids: List<EmotionId> = java.util.List.copyOf(source)

    private val idSet: Set<EmotionId> = java.util.Set.copyOf(ids)

    init {
        require(ids.size <= MAX_SIZE) { "Emotion catalog cannot contain more than $MAX_SIZE IDs" }
        require(ids.size == idSet.size) { "Emotion catalog cannot contain duplicate IDs" }
    }

    fun contains(id: EmotionId): Boolean = id in idSet

    override fun equals(other: Any?): Boolean =
        this === other || other is EmotionCatalog && ids == other.ids

    override fun hashCode(): Int = ids.hashCode()

    override fun toString(): String = "EmotionCatalog(ids=$ids)"

    companion object {
        const val MAX_SIZE = 512

        fun of(ids: Collection<EmotionId>): EmotionCatalog = EmotionCatalog(ids)
    }
}
