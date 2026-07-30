package me.whish.emotify.protocol

import java.util.UUID
import me.whish.emotify.domain.EmotionId

@JvmInline
value class RuntimeEntityId private constructor(val value: Int) {
    companion object {
        fun parse(value: Int): RuntimeEntityId? = value.takeIf { it > 0 }?.let(::RuntimeEntityId)

        fun of(value: Int): RuntimeEntityId =
            requireNotNull(parse(value)) { "Runtime entity ID must be positive: $value" }
    }
}

@JvmInline
value class EventSequence private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): EventSequence? = value.takeIf { it > 0L }?.let(::EventSequence)

        fun of(value: Long): EventSequence =
            requireNotNull(parse(value)) { "Event sequence must be positive: $value" }
    }
}

data class EmotionPlay(
    val entityId: RuntimeEntityId,
    val sourceUuid: UUID,
    val sequence: EventSequence,
    val emotionId: EmotionId,
)
