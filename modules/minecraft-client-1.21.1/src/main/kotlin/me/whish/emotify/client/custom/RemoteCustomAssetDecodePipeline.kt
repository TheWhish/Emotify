package me.whish.emotify.client.custom

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembly
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembler
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker
import me.whish.emotify.wire.v1.CustomEmojiAssetVerificationResult
import me.whish.emotify.wire.v1.CustomEmojiEncodedAssembly
import me.whish.emotify.wire.v1.CustomEmojiEncodedAssemblyResult
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec
import me.whish.emotify.wire.v1.WireDecodeViolation

enum class RemoteCustomAssetAdmission {
    ACCEPTED,
    INACTIVE_CONNECTION,
    SATURATED,
    CLOSED,
}

sealed interface RemoteCustomAssetDecodeResult<out T : Any> {
    data class Prepared<T : Any>(
        val value: T,
    ) : RemoteCustomAssetDecodeResult<T>

    data class Rejected(
        val violation: WireDecodeViolation,
    ) : RemoteCustomAssetDecodeResult<Nothing>

    data class Failed(
        val failure: RuntimeException,
    ) : RemoteCustomAssetDecodeResult<Nothing>

    data object Abandoned : RemoteCustomAssetDecodeResult<Nothing>
}

class RemoteCustomAssetDecodePipeline<T : Any>(
    private val completionExecutor: (Runnable) -> Unit,
    private val completionListener: (Long, CustomEmojiId, RemoteCustomAssetDecodeResult<T>) -> Unit,
    private val preparer: (CustomEmojiAssetAssembly) -> T,
    private val preparedDisposer: (T) -> Unit,
    private val maximumQueuedChunks: Int = CustomEmojiAssetChunker.MAXIMUM_CHUNK_COUNT,
    private val maximumQueuedBytes: Int = CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES,
    private val maximumAwaitedAssets: Int = maximumQueuedChunks,
    private val awaitTimeoutMillis: Long = DEFAULT_ASSEMBLY_TIMEOUT_MILLIS,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
    private val verifier: (CustomEmojiEncodedAssembly) -> CustomEmojiAssetVerificationResult =
        CustomEmojiEncodedAssembly::tryVerify,
) : AutoCloseable {
    init {
        require(maximumQueuedChunks > 0) { "Maximum queued custom asset chunks must be positive" }
        require(maximumQueuedBytes > 0) { "Maximum queued custom asset bytes must be positive" }
        require(maximumAwaitedAssets > 0) { "Maximum awaited custom assets must be positive" }
        require(awaitTimeoutMillis in 1..MAXIMUM_SAFE_TIMEOUT_MILLIS) {
            "Custom asset await timeout is invalid: $awaitTimeoutMillis"
        }
    }

    private val logger = System.getLogger(RemoteCustomAssetDecodePipeline::class.java.name)
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val pending = ArrayDeque<QueuedChunk>(maximumQueuedChunks)
    private val awaiting = LinkedHashMap<CustomEmojiId, AwaitedAsset>(maximumAwaitedAssets * 4 / 3 + 1)
    private val assembler = CustomEmojiAssetAssembler(awaitTimeoutMillis)
    private var activeConnectionId = 0L
    private var lifecycleGeneration = 0L
    private var generation = 0L
    private var queuedBytes = 0
    private var closed = false
    private var workerGeneration = -1L
    private var workerAssetId: CustomEmojiId? = null
    private val worker = Thread(::runWorker, "Emotify custom asset decoder").apply {
        isDaemon = true
        start()
    }

    fun begin(connectionId: Long) {
        require(connectionId > 0L) { "Client connection ID must be positive: $connectionId" }
        lock.withLock {
            check(!closed) { "Custom asset decode pipeline is closed" }
            lifecycleGeneration = Math.incrementExact(lifecycleGeneration)
            advanceGenerationLocked()
            activeConnectionId = connectionId
        }
    }

    fun submit(connectionId: Long, chunk: CustomEmojiAssetChunk): RemoteCustomAssetAdmission {
        var abandoned = emptyList<AwaitedAsset>()
        val admission = lock.withLock {
            if (closed) {
                return@withLock RemoteCustomAssetAdmission.CLOSED
            }
            if (connectionId != activeConnectionId) {
                return@withLock RemoteCustomAssetAdmission.INACTIVE_CONNECTION
            }
            val nowMillis = nowMillis()
            if (hasExpiredAwaitingLocked(nowMillis)) {
                abandoned = resetCurrentGenerationLocked()
            }
            if (
                pending.size >= maximumQueuedChunks ||
                chunk.dataLength > maximumQueuedBytes - queuedBytes
            ) {
                return@withLock RemoteCustomAssetAdmission.SATURATED
            }
            if (chunk.index == 0) {
                if (chunk.customEmojiId !in awaiting && awaiting.size >= maximumAwaitedAssets) {
                    abandoned = abandoned + resetCurrentGenerationLocked()
                }
                awaiting.remove(chunk.customEmojiId)
                awaiting[chunk.customEmojiId] = AwaitedAsset(
                    connectionId,
                    lifecycleGeneration,
                    generation,
                    chunk.customEmojiId,
                    nowMillis,
                )
            }
            pending.addLast(QueuedChunk(connectionId, generation, chunk))
            queuedBytes += chunk.dataLength
            available.signalAll()
            RemoteCustomAssetAdmission.ACCEPTED
        }
        abandoned.forEach(::dispatchAbandoned)
        return admission
    }

    fun isAwaiting(connectionId: Long, customEmojiId: CustomEmojiId): Boolean {
        var abandoned = emptyList<AwaitedAsset>()
        val result = lock.withLock {
            if (closed || connectionId != activeConnectionId) {
                return@withLock false
            }
            if (hasExpiredAwaitingLocked(nowMillis())) {
                abandoned = resetCurrentGenerationLocked()
                return@withLock false
            }
            awaiting[customEmojiId]?.generation == generation
        }
        abandoned.forEach(::dispatchAbandoned)
        return result
    }

    fun disconnect(connectionId: Long) {
        lock.withLock {
            if (connectionId != activeConnectionId) {
                return
            }
            lifecycleGeneration = Math.incrementExact(lifecycleGeneration)
            advanceGenerationLocked()
            activeConnectionId = 0L
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) {
                return
            }
            closed = true
            lifecycleGeneration = Math.incrementExact(lifecycleGeneration)
            advanceGenerationLocked()
            activeConnectionId = 0L
        }
        worker.interrupt()
    }

    private fun runWorker() {
        while (true) {
            val work = try {
                takeNext()
            } catch (_: InterruptedException) {
                if (isClosed()) {
                    return
                }
                continue
            } ?: return
            when (work) {
                is WorkerWork.Reset -> {
                    assembler.reset()
                    workerAssetId = null
                    workerGeneration = work.generation
                    work.abandoned.forEach(::dispatchAbandoned)
                }
                is WorkerWork.Decode -> process(work.queued)
            }
        }
    }

    private fun process(queued: QueuedChunk) {
        if (!isCurrent(queued)) {
            return
        }
        if (queued.chunk.index == 0) {
            val previousId = workerAssetId
            if (previousId != null && previousId != queued.chunk.customEmojiId) {
                abandonAwaited(queued, previousId)?.let(::dispatchAbandoned)
            }
            workerAssetId = queued.chunk.customEmojiId
        }
        val resultId = workerAssetId ?: queued.chunk.customEmojiId
        val result: RemoteCustomAssetDecodeResult<T> = try {
            decode(queued)
        } catch (failure: RuntimeException) {
            assembler.reset()
            RemoteCustomAssetDecodeResult.Failed(failure)
        } ?: return
        workerAssetId = null
        dispatch(queued, resultId, result)
    }

    private fun decode(queued: QueuedChunk): RemoteCustomAssetDecodeResult<T>? = when (
        val collected = assembler.tryAcceptEncodedAssembly(
            queued.chunk,
            nowMillis(),
        )
    ) {
        CustomEmojiEncodedAssemblyResult.Pending -> null
        is CustomEmojiEncodedAssemblyResult.Rejected -> RemoteCustomAssetDecodeResult.Rejected(collected.violation)
        is CustomEmojiEncodedAssemblyResult.Completed -> {
            if (!isCurrent(queued)) {
                null
            } else {
                when (val verified = verifier(collected.assembly)) {
                    is CustomEmojiAssetVerificationResult.Verified -> {
                        val prepared = preparer(verified.assembly)
                        if (isCurrent(queued)) {
                            RemoteCustomAssetDecodeResult.Prepared(prepared)
                        } else {
                            disposePrepared(prepared)
                            null
                        }
                    }
                    is CustomEmojiAssetVerificationResult.Rejected ->
                        RemoteCustomAssetDecodeResult.Rejected(verified.violation)
                }
            }
        }
    }

    private fun dispatch(
        queued: QueuedChunk,
        customEmojiId: CustomEmojiId,
        result: RemoteCustomAssetDecodeResult<T>,
    ) {
        val task = Runnable {
            if (isCurrent(queued)) {
                clearAwaited(queued, customEmojiId)
                completionListener(queued.connectionId, customEmojiId, result)
            } else if (result is RemoteCustomAssetDecodeResult.Prepared) {
                disposePrepared(result.value)
            }
        }
        try {
            completionExecutor(task)
        } catch (failure: RuntimeException) {
            clearAwaited(queued, customEmojiId)
            if (result is RemoteCustomAssetDecodeResult.Prepared) {
                disposePrepared(result.value, failure)
            }
            logger.log(System.Logger.Level.ERROR, "Failed to schedule custom asset decode completion", failure)
        }
    }

    private fun dispatchAbandoned(awaited: AwaitedAsset) {
        val task = Runnable {
            if (shouldDispatchAbandoned(awaited)) {
                completionListener(
                    awaited.connectionId,
                    awaited.customEmojiId,
                    RemoteCustomAssetDecodeResult.Abandoned,
                )
            }
        }
        try {
            completionExecutor(task)
        } catch (failure: RuntimeException) {
            logger.log(System.Logger.Level.ERROR, "Failed to schedule abandoned custom asset completion", failure)
        }
    }

    private fun takeNext(): WorkerWork? = lock.withLock {
        var work: WorkerWork? = null
        while (work == null && !closed) {
            val nowMillis = nowMillis()
            work = when {
                workerGeneration != generation -> WorkerWork.Reset(generation, emptyList())
                hasExpiredAwaitingLocked(nowMillis) -> {
                    val abandoned = resetCurrentGenerationLocked()
                    WorkerWork.Reset(generation, abandoned)
                }
                pending.isNotEmpty() -> WorkerWork.Decode(
                    pending.removeFirst().also { queued -> queuedBytes -= queued.chunk.dataLength },
                )
                else -> {
                    val waitNanos = waitNanosUntilExpirationLocked(nowMillis)
                    if (waitNanos == null) {
                        available.await()
                    } else {
                        available.awaitNanos(waitNanos)
                    }
                    null
                }
            }
        }
        work
    }

    private fun advanceGenerationLocked() {
        generation = Math.incrementExact(generation)
        pending.clear()
        awaiting.clear()
        queuedBytes = 0
        available.signalAll()
    }

    private fun resetCurrentGenerationLocked(): List<AwaitedAsset> {
        val abandoned = java.util.List.copyOf(awaiting.values)
        advanceGenerationLocked()
        return abandoned
    }

    private fun hasExpiredAwaitingLocked(nowMillis: Long): Boolean =
        awaiting.values.any { awaited -> hasExpired(awaited.startedMillis, nowMillis) }

    private fun waitNanosUntilExpirationLocked(nowMillis: Long): Long? {
        val remainingMillis = awaiting.values.minOfOrNull { awaited ->
            (awaitTimeoutMillis - elapsedMillis(awaited.startedMillis, nowMillis)).coerceAtLeast(0L)
        } ?: return null
        return (remainingMillis * NANOS_PER_MILLISECOND).coerceAtLeast(1L)
    }

    private fun hasExpired(startedMillis: Long, nowMillis: Long): Boolean =
        nowMillis < startedMillis || elapsedMillis(startedMillis, nowMillis) >= awaitTimeoutMillis

    private fun elapsedMillis(startedMillis: Long, nowMillis: Long): Long =
        (nowMillis - startedMillis).coerceAtLeast(0L)

    private fun abandonAwaited(queued: QueuedChunk, customEmojiId: CustomEmojiId): AwaitedAsset? = lock.withLock {
        if (queued.connectionId != activeConnectionId || queued.generation != generation) {
            return@withLock null
        }
        awaiting[customEmojiId]?.takeIf { awaited -> awaited.generation == queued.generation }?.also {
            awaiting.remove(customEmojiId)
        }
    }

    private fun clearAwaited(queued: QueuedChunk, customEmojiId: CustomEmojiId) {
        lock.withLock {
            if (queued.connectionId != activeConnectionId || queued.generation != generation) {
                return
            }
            if (awaiting[customEmojiId]?.generation == queued.generation) {
                awaiting.remove(customEmojiId)
            }
        }
    }

    private fun isCurrent(queued: QueuedChunk): Boolean = lock.withLock {
        !closed && queued.connectionId == activeConnectionId && queued.generation == generation
    }

    private fun shouldDispatchAbandoned(awaited: AwaitedAsset): Boolean = lock.withLock {
        if (
            closed ||
            awaited.connectionId != activeConnectionId ||
            awaited.lifecycleGeneration != lifecycleGeneration
        ) {
            return@withLock false
        }
        val replacement = awaiting[awaited.customEmojiId]
        replacement == null || replacement.generation <= awaited.generation
    }

    private fun isClosed(): Boolean = lock.withLock { closed }

    private fun nowMillis(): Long = timeSource.nowNanos() / NANOS_PER_MILLISECOND

    private fun disposePrepared(prepared: T, parentFailure: RuntimeException? = null) {
        try {
            preparedDisposer(prepared)
        } catch (cleanupFailure: RuntimeException) {
            if (parentFailure == null) {
                logger.log(System.Logger.Level.ERROR, "Failed to dispose prepared custom asset", cleanupFailure)
            } else {
                parentFailure.addSuppressed(cleanupFailure)
            }
        }
    }

    private sealed interface WorkerWork {
        data class Reset(
            val generation: Long,
            val abandoned: List<AwaitedAsset>,
        ) : WorkerWork

        data class Decode(
            val queued: QueuedChunk,
        ) : WorkerWork
    }

    private data class AwaitedAsset(
        val connectionId: Long,
        val lifecycleGeneration: Long,
        val generation: Long,
        val customEmojiId: CustomEmojiId,
        val startedMillis: Long,
    )

    private data class QueuedChunk(
        val connectionId: Long,
        val generation: Long,
        val chunk: CustomEmojiAssetChunk,
    )

    companion object {
        private const val DEFAULT_ASSEMBLY_TIMEOUT_MILLIS = 10_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAXIMUM_SAFE_TIMEOUT_MILLIS = Long.MAX_VALUE / NANOS_PER_MILLISECOND
    }
}
