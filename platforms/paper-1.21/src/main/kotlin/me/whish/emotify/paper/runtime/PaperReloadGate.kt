package me.whish.emotify.paper.runtime

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

@JvmInline
value class PaperReloadTicket internal constructor(val generation: Long)

sealed interface PaperReloadAdmission {
    data class Admitted(val ticket: PaperReloadTicket) : PaperReloadAdmission

    data object Pending : PaperReloadAdmission

    data object RateLimited : PaperReloadAdmission
}

class PaperReloadGate(
    timeSource: MonotonicTimeSource,
) {
    private val monitor = Any()
    private val requests = TokenBucket(1, 1, timeSource)
    private var generation = 0L
    private var pending = false

    fun tryBegin(): PaperReloadAdmission = synchronized(monitor) {
        if (pending) {
            return@synchronized PaperReloadAdmission.Pending
        }
        if (!requests.tryConsume()) {
            return@synchronized PaperReloadAdmission.RateLimited
        }
        pending = true
        PaperReloadAdmission.Admitted(PaperReloadTicket(generation))
    }

    fun complete(ticket: PaperReloadTicket): Boolean = synchronized(monitor) {
        if (!pending || ticket.generation != generation) {
            return@synchronized false
        }
        pending = false
        true
    }

    fun invalidate() {
        synchronized(monitor) {
            generation = Math.incrementExact(generation)
            pending = false
            requests.reset()
        }
    }
}
