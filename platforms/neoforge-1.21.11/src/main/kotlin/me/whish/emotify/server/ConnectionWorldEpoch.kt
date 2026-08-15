package me.whish.emotify.server

import java.util.concurrent.atomic.AtomicLong

class ConnectionWorldEpoch(
    initialValue: Long = INITIAL_VALUE,
) {
    private val value = AtomicLong(initialValue)

    init {
        require(initialValue > 0L) { "Connection world epoch must be positive: $initialValue" }
    }

    fun current(): Long = value.get()

    fun advance(): Long = value.updateAndGet { current ->
        check(current < Long.MAX_VALUE) { "Connection world epoch space is exhausted" }
        current + 1L
    }

    companion object {
        const val INITIAL_VALUE = 1L
    }
}
