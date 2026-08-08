package me.whish.emotify.server.core

import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec

class CustomAssetIngressBudget(
    maximumRetainedBytes: Int = DEFAULT_MAXIMUM_RETAINED_BYTES,
    startBurst: Int = DEFAULT_START_BURST,
    startRefillPerSecond: Int = DEFAULT_START_REFILL_PER_SECOND,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val maximumRetainedBytes = maximumRetainedBytes.toLong()
    private val starts = TokenBucket(startBurst, startRefillPerSecond, timeSource)
    private val leases = LinkedHashSet<Lease>()
    private var retainedBytes = 0L

    init {
        require(maximumRetainedBytes >= MAXIMUM_SINGLE_RESERVATION_BYTES) {
            "Custom asset ingress budget must fit one maximum transfer: $maximumRetainedBytes"
        }
    }

    fun tryAcquire(byteCount: Int, onExpired: () -> Unit = {}): Lease? {
        require(byteCount in 1..CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES) {
            "Custom asset ingress size is outside protocol limits: $byteCount"
        }
        expire()
        val reservedBytes = Math.multiplyExact(byteCount, ENCODED_MEMORY_MULTIPLIER)
        if (reservedBytes > maximumRetainedBytes - retainedBytes || !starts.tryConsume()) {
            return null
        }
        val lease = Lease(reservedBytes, timeSource.nowNanos() + LEASE_TIMEOUT_NANOS, ::release, onExpired)
        leases += lease
        retainedBytes += reservedBytes
        return lease
    }

    fun reset() {
        leases.toList().forEach(Lease::close)
        starts.reset()
    }

    internal fun retainedBytes(): Long {
        expire()
        return retainedBytes
    }

    private fun expire() {
        val now = timeSource.nowNanos()
        leases.toList().forEach { lease ->
            if (now >= lease.expiresAtNanos) {
                lease.expire()
            }
        }
    }

    private fun release(lease: Lease) {
        if (leases.remove(lease)) {
            retainedBytes -= lease.byteCount
        }
    }

    class Lease internal constructor(
        internal val byteCount: Int,
        internal val expiresAtNanos: Long,
        private val release: (Lease) -> Unit,
        private val onExpired: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(this)
            }
        }

        internal fun isActive(nowNanos: Long): Boolean = !closed.get() && nowNanos < expiresAtNanos

        internal fun expire() {
            if (closed.compareAndSet(false, true)) {
                release(this)
                onExpired()
            }
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_RETAINED_BYTES = 16 * 1_024 * 1_024
        const val DEFAULT_START_BURST = 16
        const val DEFAULT_START_REFILL_PER_SECOND = 8
        private const val ENCODED_MEMORY_MULTIPLIER = 3
        private const val MAXIMUM_SINGLE_RESERVATION_BYTES =
            CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES * ENCODED_MEMORY_MULTIPLIER
        private const val LEASE_TIMEOUT_NANOS = 10_000_000_000L
    }
}
