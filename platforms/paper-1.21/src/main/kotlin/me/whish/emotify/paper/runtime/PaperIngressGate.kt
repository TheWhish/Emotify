package me.whish.emotify.paper.runtime

import java.util.concurrent.atomic.AtomicBoolean
import me.whish.emotify.server.core.ConnectionKey

enum class PaperIngressLane {
    SERIAL,
    CUSTOM_ASSET_CHUNK,
}

class PaperIngressGate(
    maximumOutstanding: Int,
) {
    private val lock = Any()
    private val reservations = HashMap<ConnectionKey, ConnectionReservation>()
    private var maximumOutstanding = validatedMaximum(maximumOutstanding)
    private var outstanding = 0

    val outstandingCount: Int
        get() = synchronized(lock) { outstanding }

    fun tryAcquire(
        connection: ConnectionKey,
        lane: PaperIngressLane = PaperIngressLane.SERIAL,
        maximumForLane: Int = 1,
    ): PaperIngressLease? {
        require(maximumForLane > 0) {
            "Maximum outstanding Paper ingress tasks per lane must be positive: $maximumForLane"
        }
        val reservation = synchronized(lock) {
            val existing = reservations[connection]
            if (outstanding >= maximumOutstanding || existing != null && existing.count(lane) >= maximumForLane) {
                return null
            }
            val active = existing ?: ConnectionReservation().also { created -> reservations[connection] = created }
            active.increment(lane)
            outstanding++
            active
        }
        return PaperIngressLease { release(connection, reservation, lane) }
    }

    fun clear() {
        synchronized(lock) {
            reservations.clear()
            outstanding = 0
        }
    }

    fun reconfigure(maximumOutstanding: Int) {
        val replacement = validatedMaximum(maximumOutstanding)
        synchronized(lock) {
            this.maximumOutstanding = replacement
        }
    }

    private fun release(
        connection: ConnectionKey,
        reservation: ConnectionReservation,
        lane: PaperIngressLane,
    ) {
        synchronized(lock) {
            if (reservations[connection] !== reservation) {
                return
            }
            reservation.decrement(lane)
            outstanding--
            if (reservation.isEmpty()) {
                reservations.remove(connection)
            }
        }
    }

    private fun validatedMaximum(value: Int): Int {
        require(value > 0) { "Maximum outstanding Paper ingress tasks must be positive: $value" }
        return value
    }

    private class ConnectionReservation {
        private var serial = 0
        private var customAssetChunks = 0

        fun count(lane: PaperIngressLane): Int = when (lane) {
            PaperIngressLane.SERIAL -> serial
            PaperIngressLane.CUSTOM_ASSET_CHUNK -> customAssetChunks
        }

        fun increment(lane: PaperIngressLane) {
            when (lane) {
                PaperIngressLane.SERIAL -> serial++
                PaperIngressLane.CUSTOM_ASSET_CHUNK -> customAssetChunks++
            }
        }

        fun decrement(lane: PaperIngressLane) {
            when (lane) {
                PaperIngressLane.SERIAL -> {
                    check(serial > 0) { "Paper serial ingress reservation count became invalid" }
                    serial--
                }
                PaperIngressLane.CUSTOM_ASSET_CHUNK -> {
                    check(customAssetChunks > 0) { "Paper custom asset ingress reservation count became invalid" }
                    customAssetChunks--
                }
            }
        }

        fun isEmpty(): Boolean = serial == 0 && customAssetChunks == 0
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
