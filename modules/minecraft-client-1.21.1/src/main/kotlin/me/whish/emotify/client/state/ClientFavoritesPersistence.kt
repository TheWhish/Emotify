package me.whish.emotify.client.state

import java.util.concurrent.Executor

class SerializedSnapshotStore<T : Any>(
    private val loader: () -> T,
    executor: Executor,
    sink: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    private val stateLock = Any()
    private val writer = LatestSnapshotWriter(executor, sink, onFailure)
    private var initialized = false
    private var current: T? = null

    fun load(): T = synchronized(stateLock) {
        if (!initialized) {
            current = loader()
            initialized = true
        }
        checkNotNull(current)
    }

    fun submit(snapshot: T) {
        synchronized(stateLock) {
            current = snapshot
            initialized = true
        }
        writer.submit(snapshot)
    }
}

class FailureLogGate(
    private val intervalNanos: Long,
) {
    private var initialized = false
    private var lastAcquiredNanos = 0L

    init {
        require(intervalNanos > 0L) { "Failure log interval must be positive: $intervalNanos" }
    }

    @Synchronized
    fun tryAcquire(nowNanos: Long): Boolean {
        if (!initialized || nowNanos < lastAcquiredNanos) {
            initialized = true
            lastAcquiredNanos = nowNanos
            return true
        }
        if (nowNanos - lastAcquiredNanos < intervalNanos) {
            return false
        }
        lastAcquiredNanos = nowNanos
        return true
    }
}

private class LatestSnapshotWriter<T : Any>(
    private val executor: Executor,
    private val sink: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var scheduled = false

    fun submit(snapshot: T) {
        var schedulingFailure: RuntimeException? = null
        synchronized(lock) {
            pending = snapshot
            if (!scheduled) {
                scheduled = true
                try {
                    executor.execute(::drain)
                } catch (error: RuntimeException) {
                    scheduled = false
                    schedulingFailure = error
                }
            }
        }
        schedulingFailure?.let(onFailure)
    }

    private fun drain() {
        while (true) {
            val snapshot = synchronized(lock) {
                val next = pending
                if (next == null) {
                    scheduled = false
                    return
                }
                pending = null
                next
            }
            try {
                sink(snapshot)
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
