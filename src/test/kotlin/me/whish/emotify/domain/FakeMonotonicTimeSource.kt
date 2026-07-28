package me.whish.emotify.domain

import kotlin.time.Duration

class FakeMonotonicTimeSource(initialNanos: Long = 0L) : MonotonicTimeSource {
    private var currentNanos = initialNanos

    override fun nowNanos(): Long = currentNanos

    fun advanceBy(duration: Duration) {
        currentNanos += duration.inWholeNanoseconds
    }

    fun rewindBy(duration: Duration) {
        currentNanos -= duration.inWholeNanoseconds
    }
}
