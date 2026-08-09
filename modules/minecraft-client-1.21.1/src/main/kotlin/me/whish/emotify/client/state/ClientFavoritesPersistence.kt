package me.whish.emotify.client.state

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class SerializedSnapshotStore<T : Any>(
    private val loader: () -> T,
    executor: Executor,
    sink: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
    maximumWriteAttempts: Int = DEFAULT_MAXIMUM_WRITE_ATTEMPTS,
    retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    private val stateLock = Any()
    private val writer = LatestSnapshotWriter(
        executor,
        sink,
        onFailure,
        maximumWriteAttempts,
        retryDelayMillis,
    )
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
            writer.submit(snapshot)
        }
    }

    fun update(transform: (T) -> T): T = synchronized(stateLock) {
        if (!initialized) {
            current = loader()
            initialized = true
        }
        val previous = checkNotNull(current)
        transform(previous).also { snapshot ->
            if (snapshot !== previous) {
                current = snapshot
                writer.submit(snapshot)
            }
        }
    }

    fun updateInMemory(transform: (T) -> T): T = synchronized(stateLock) {
        if (!initialized) {
            current = loader()
            initialized = true
        }
        val previous = checkNotNull(current)
        transform(previous).also { snapshot ->
            if (snapshot !== previous) {
                current = snapshot
            }
        }
    }

    fun flush(timeout: Long, unit: TimeUnit): Boolean = writer.flush(timeout, unit)

    companion object {
        const val DEFAULT_MAXIMUM_WRITE_ATTEMPTS = 3
        const val DEFAULT_RETRY_DELAY_MILLIS = 250L
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
    private val maximumWriteAttempts: Int,
    private val retryDelayMillis: Long,
) {
    private val lock = Any()
    private var pending: PendingSnapshot<T>? = null
    private var scheduled = false
    private var submittedSequence = 0L
    private var persistedSequence = 0L
    private val flushWaiters = ArrayList<FlushWaiter>()

    init {
        require(maximumWriteAttempts > 0) { "Maximum write attempts must be positive: $maximumWriteAttempts" }
        require(retryDelayMillis >= 0L) { "Retry delay must not be negative: $retryDelayMillis" }
    }

    fun submit(snapshot: T) {
        var schedulingFailure: RuntimeException? = null
        synchronized(lock) {
            submittedSequence++
            pending = PendingSnapshot(submittedSequence, snapshot, 1)
            if (!scheduled) {
                scheduled = true
                try {
                    executor.execute(::drain)
                } catch (error: RuntimeException) {
                    scheduled = false
                    releaseFlushWaiters()
                    schedulingFailure = error
                }
            }
        }
        schedulingFailure?.let(onFailure)
    }

    fun flush(timeout: Long, unit: TimeUnit): Boolean {
        require(timeout >= 0L) { "Flush timeout must not be negative: $timeout" }
        val targetSequence: Long
        val waiter = synchronized(lock) {
            targetSequence = submittedSequence
            if (persistedSequence >= targetSequence) {
                return true
            }
            if (!scheduled) {
                return false
            }
            FlushWaiter(targetSequence).also(flushWaiters::add)
        }
        val completed = try {
            waiter.latch.await(timeout, unit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(lock) {
                flushWaiters.remove(waiter)
            }
            return false
        }
        return synchronized(lock) {
            if (!completed) {
                flushWaiters.remove(waiter)
            }
            persistedSequence >= targetSequence
        }
    }

    private fun drain() {
        while (true) {
            val candidate = synchronized(lock) {
                val next = pending
                if (next == null) {
                    scheduled = false
                    null
                } else {
                    next
                }
            } ?: return
            try {
                sink(candidate.value)
            } catch (error: Exception) {
                val retryAttempt = synchronized(lock) {
                    val latest = pending
                    if (latest?.sequence != candidate.sequence) {
                        null
                    } else if (candidate.attempt < maximumWriteAttempts) {
                        val retry = candidate.copy(attempt = candidate.attempt + 1)
                        pending = retry
                        retry.attempt
                    } else {
                        scheduled = false
                        releaseFlushWaiters()
                        -1
                    }
                }
                if (retryAttempt != null && retryAttempt > 0) {
                    try {
                        scheduleRetry(retryAttempt)
                    } catch (schedulingError: RuntimeException) {
                        synchronized(lock) {
                            scheduled = false
                            releaseFlushWaiters()
                        }
                        onFailure(schedulingError)
                    }
                }
                onFailure(error)
                if (retryAttempt != null) {
                    return
                }
                continue
            }
            synchronized(lock) {
                persistedSequence = maxOf(persistedSequence, candidate.sequence)
                if (pending?.sequence == candidate.sequence) {
                    pending = null
                }
                releaseSatisfiedFlushWaiters()
            }
        }
    }

    private fun scheduleRetry(attempt: Int) {
        if (retryDelayMillis == 0L) {
            executor.execute(::drain)
            return
        }
        val delay = retryDelayMillis * (attempt - 1L)
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor).execute(::drain)
    }

    private fun releaseSatisfiedFlushWaiters() {
        val iterator = flushWaiters.iterator()
        while (iterator.hasNext()) {
            val waiter = iterator.next()
            if (persistedSequence >= waiter.targetSequence) {
                waiter.latch.countDown()
                iterator.remove()
            }
        }
    }

    private fun releaseFlushWaiters() {
        flushWaiters.forEach { waiter -> waiter.latch.countDown() }
        flushWaiters.clear()
    }

    private data class PendingSnapshot<T : Any>(
        val sequence: Long,
        val value: T,
        val attempt: Int,
    )

    private class FlushWaiter(
        val targetSequence: Long,
        val latch: CountDownLatch = CountDownLatch(1),
    )
}
