package me.whish.emotify.client

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.util.UUID
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId

data class ActiveEmotion(
    val entityId: RuntimeEntityId,
    val sourceUuid: UUID,
    val sequence: EventSequence,
    val emotionId: EmotionId,
    val startedAtNanos: Long,
)

enum class EmotionActivationResult {
    ADDED,
    REPLACED,
    CAPACITY_REJECTED,
    STALE_CONNECTION,
    STALE_SEQUENCE,
    UNKNOWN_EMOTION,
}

class ClientActiveEmotionStore(
    private val timeSource: MonotonicTimeSource,
    private val allowedCatalog: EmotionCatalog = EmotionCatalog.BUILT_IN,
    private val maximumActive: Int = MAXIMUM_ACTIVE,
) {
    private val activeByEntityId = Int2ObjectOpenHashMap<ActiveEmotion>()
    private var activeConnectionId = 0L
    private var lastObservedNanos = 0L
    private var hasObservedTime = false

    init {
        require(maximumActive > 0) { "Maximum active emotion count must be positive: $maximumActive" }
    }

    val size: Int
        get() = activeByEntityId.size

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        activeByEntityId.clear()
        activeConnectionId = connectionId
        hasObservedTime = false
    }

    fun activate(connectionId: Long, play: EmotionPlay): EmotionActivationResult {
        if (connectionId != activeConnectionId) {
            return EmotionActivationResult.STALE_CONNECTION
        }
        if (!allowedCatalog.contains(play.emotionId)) {
            return EmotionActivationResult.UNKNOWN_EMOTION
        }

        val nowNanos = observeTime()
        removeExpiredAt(nowNanos)
        val entityId = play.entityId.value
        val indexedEmotion = activeByEntityId[entityId]
        if (indexedEmotion != null && play.sequence.value <= indexedEmotion.sequence.value) {
            return EmotionActivationResult.STALE_SEQUENCE
        }

        val previousEntityId = findEntityId(play.sourceUuid, entityId)
        val previousEmotion = activeByEntityId[previousEntityId]
        if (previousEmotion != null && play.sequence.value <= previousEmotion.sequence.value) {
            return EmotionActivationResult.STALE_SEQUENCE
        }

        val replacesExisting = indexedEmotion != null || previousEmotion != null
        if (!replacesExisting && activeByEntityId.size >= maximumActive) {
            removeVisualExpiredForCapacityAt(nowNanos)
            if (activeByEntityId.size >= maximumActive) {
                return EmotionActivationResult.CAPACITY_REJECTED
            }
        }
        if (previousEmotion != null) {
            activeByEntityId.remove(previousEntityId)
        }

        activeByEntityId[entityId] = ActiveEmotion(
            play.entityId,
            play.sourceUuid,
            play.sequence,
            play.emotionId,
            nowNanos,
        )
        return if (replacesExisting) {
            EmotionActivationResult.REPLACED
        } else {
            EmotionActivationResult.ADDED
        }
    }

    fun visibleFor(entityId: Int, sourceUuid: UUID): ActiveEmotion? {
        val active = find(entityId, sourceUuid) ?: return null
        return active.takeUnless { isVisualExpired(it, observeTime()) }
    }

    fun shouldHideNameTagFor(entityId: Int, sourceUuid: UUID): Boolean {
        val active = find(entityId, sourceUuid) ?: return false
        return !isRetentionExpired(active, observeTime())
    }

    fun find(entityId: Int, sourceUuid: UUID): ActiveEmotion? =
        activeByEntityId[entityId]?.takeIf { active -> active.sourceUuid == sourceUuid }

    fun removeExpired(): Int = removeExpiredAt(observeTime())

    fun discard(entityId: Int, sourceUuid: UUID): Boolean {
        val active = activeByEntityId[entityId] ?: return false
        if (active.sourceUuid != sourceUuid) {
            return false
        }
        activeByEntityId.remove(entityId)
        return true
    }

    fun discardIf(predicate: (ActiveEmotion) -> Boolean): Int {
        var removed = 0
        val iterator = activeByEntityId.values.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next())) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    fun clearWorld(connectionId: Long) {
        if (connectionId == activeConnectionId) {
            activeByEntityId.clear()
        }
    }

    fun disconnect(connectionId: Long) {
        if (connectionId != activeConnectionId) {
            return
        }
        activeByEntityId.clear()
        activeConnectionId = 0L
        hasObservedTime = false
    }

    private fun findEntityId(sourceUuid: UUID, excludedEntityId: Int): Int {
        val iterator = activeByEntityId.int2ObjectEntrySet().fastIterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.intKey != excludedEntityId && entry.value.sourceUuid == sourceUuid) {
                return entry.intKey
            }
        }
        return MISSING_ENTITY_ID
    }

    private fun removeExpiredAt(nowNanos: Long): Int {
        var removed = 0
        val iterator = activeByEntityId.values.iterator()
        while (iterator.hasNext()) {
            if (isRetentionExpired(iterator.next(), nowNanos)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun removeVisualExpiredForCapacityAt(nowNanos: Long) {
        val iterator = activeByEntityId.values.iterator()
        while (activeByEntityId.size >= maximumActive && iterator.hasNext()) {
            if (isVisualExpired(iterator.next(), nowNanos)) {
                iterator.remove()
            }
        }
    }

    private fun observeTime(): Long {
        val nowNanos = timeSource.nowNanos()
        if (hasObservedTime) {
            check(nowNanos - lastObservedNanos >= 0L) { "Monotonic time source moved backwards" }
        }
        lastObservedNanos = nowNanos
        hasObservedTime = true
        return nowNanos
    }

    private fun isVisualExpired(active: ActiveEmotion, nowNanos: Long): Boolean {
        val elapsedNanos = nowNanos - active.startedAtNanos
        check(elapsedNanos >= 0L) { "Active emotion start time is in the future" }
        return elapsedNanos >= VISUAL_LIFETIME_NANOS
    }

    private fun isRetentionExpired(active: ActiveEmotion, nowNanos: Long): Boolean {
        val elapsedNanos = nowNanos - active.startedAtNanos
        check(elapsedNanos >= 0L) { "Active emotion start time is in the future" }
        return elapsedNanos >= RETENTION_LIFETIME_NANOS
    }

    companion object {
        const val MAXIMUM_ACTIVE = 256

        private const val MISSING_ENTITY_ID = 0
        private val VISUAL_LIFETIME_NANOS =
            (EmotionAnimation.DURATION_MILLIS * NANOSECONDS_PER_MILLISECOND).toLong()
        private val RETENTION_LIFETIME_NANOS =
            VISUAL_LIFETIME_NANOS + NAME_TAG_GRACE_MILLIS * NANOSECONDS_PER_MILLISECOND
        private const val NAME_TAG_GRACE_MILLIS = 100L
        private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
    }
}
