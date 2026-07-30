package me.whish.emotify.paper.runtime

import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.server.core.ConnectionKey

class PaperIngressGate(
    maximumOutstanding: Int,
) {
    private val lock = Any()
    private val reservations = HashMap<ConnectionKey, Any>()
    private var maximumOutstanding = validatedMaximum(maximumOutstanding)

    val outstandingCount: Int
        get() = synchronized(lock) { reservations.size }

    fun tryAcquire(connection: ConnectionKey): PaperIngressLease? {
        val reservation = synchronized(lock) {
            if (reservations.containsKey(connection) || reservations.size >= maximumOutstanding) {
                return null
            }
            Any().also { created -> reservations[connection] = created }
        }
        return PaperIngressLease { release(connection, reservation) }
    }

    fun clear() {
        synchronized(lock) {
            reservations.clear()
        }
    }

    fun reconfigure(maximumOutstanding: Int) {
        val replacement = validatedMaximum(maximumOutstanding)
        synchronized(lock) {
            this.maximumOutstanding = replacement
        }
    }

    private fun release(connection: ConnectionKey, reservation: Any) {
        synchronized(lock) {
            reservations.remove(connection, reservation)
        }
    }

    private fun validatedMaximum(value: Int): Int {
        require(value > 0) { "Maximum outstanding Paper ingress tasks must be positive: $value" }
        return value
    }
}

class PaperIngressLease internal constructor(
    private val releaseAction: () -> Unit,
) {
    private val active = AtomicBoolean(true)

    fun release() {
        if (active.compareAndSet(true, false)) {
            releaseAction()
        }
    }
}
