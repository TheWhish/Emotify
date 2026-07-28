package me.whish.emotify.server

import me.whish.emotify.protocol.EventSequence

class ServerEventSequence(
    initialValue: Long = 0L,
) {
    private var currentValue = initialValue

    init {
        require(initialValue >= 0L) { "Initial event sequence must not be negative: $initialValue" }
    }

    fun nextOrNull(): EventSequence? {
        if (currentValue == Long.MAX_VALUE) {
            return null
        }
        currentValue += 1L
        return EventSequence.of(currentValue)
    }

    fun hasCapacity(): Boolean = currentValue < Long.MAX_VALUE

    fun reset() {
        currentValue = 0L
    }
}
