package me.whish.emotify.server.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import me.whish.emotify.domain.CustomEmojiId
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.wire.v1.CustomEmojiAssetVerificationResult
import me.whish.emotify.wire.v1.CustomEmojiEncodedAssembly
import me.whish.emotify.wire.v1.WireDecodeViolation

enum class CustomAssetUploadRejection {
    STALE_CONNECTION,
    PERMISSION_DENIED,
    SERVER_DISABLED,
    CUSTOM_EMOJIS_DISABLED,
    PROTOCOL_UNSUPPORTED,
    RATE_LIMITED,
    BUSY,
    INVALID_TRANSFER,
    VERIFICATION_FAILED,
    POLICY_REJECTED,
    QUEUE_SATURATED,
}

sealed interface CustomAssetUploadPreparation {
    data object Pending : CustomAssetUploadPreparation

    data class VerificationRequired(
        val task: CustomAssetVerificationTask,
    ) : CustomAssetUploadPreparation

    data class Rejected(
        val reason: CustomAssetUploadRejection,
    ) : CustomAssetUploadPreparation
}

sealed interface CustomAssetUploadCommit {
    data class Accepted(
        val resumedSelection: ServerSelectionResult?,
    ) : CustomAssetUploadCommit

    data class Rejected(
        val reason: CustomAssetUploadRejection,
        val resumedSelection: ServerSelectionResult?,
    ) : CustomAssetUploadCommit

    data object Stale : CustomAssetUploadCommit
}

enum class CustomAssetUploadCancellation {
    Cancelled,
    VerificationStarted,
    Stale,
}

class CustomAssetVerificationTask internal constructor(
    val connection: ConnectionKey,
    internal val ticket: SessionCustomAssetVerificationTicket,
) {
    val customEmojiId: CustomEmojiId
        get() = ticket.assembly.customEmojiId

    private val state = AtomicReference(State.READY)

    fun verify(): CustomAssetVerificationCompletion {
        check(state.compareAndSet(State.READY, State.VERIFYING)) {
            "Custom asset verification task is not ready: ${state.get()}"
        }
        return try {
            val result = ticket.assembly.tryVerify()
            check(state.compareAndSet(State.VERIFYING, State.VERIFIED)) {
                "Custom asset verification task state changed during verification"
            }
            CustomAssetVerificationCompletion(this, result)
        } catch (failure: Exception) {
            check(state.compareAndSet(State.VERIFYING, State.VERIFIED)) {
                "Custom asset verification task state changed after failure"
            }
            failedCompletion(failure)
        }
    }

    internal fun cancelBeforeVerification(): Boolean = state.compareAndSet(State.READY, State.CANCELLED)

    internal fun recoverFailure(failure: Exception): CustomAssetVerificationCompletion? {
        while (true) {
            when (val current = state.get()) {
                State.CANCELLED -> return null
                State.VERIFIED -> return failedCompletion(failure)
                State.READY,
                State.VERIFYING,
                -> if (state.compareAndSet(current, State.VERIFIED)) {
                    return failedCompletion(failure)
                }
            }
        }
    }

    private fun failedCompletion(failure: Exception): CustomAssetVerificationCompletion =
        CustomAssetVerificationCompletion(
            this,
            CustomEmojiAssetVerificationResult.Rejected(WireDecodeViolation.INVALID_CUSTOM_EMOJI),
            failure,
        )

    private enum class State {
        READY,
        VERIFYING,
        VERIFIED,
        CANCELLED,
    }
}

class CustomAssetVerificationCompletion internal constructor(
    val task: CustomAssetVerificationTask,
    internal val result: CustomEmojiAssetVerificationResult,
    internal val failure: Exception? = null,
)

sealed interface CustomAssetVerificationQueueEvent {
    data class Completed(
        val completion: CustomAssetVerificationCompletion,
    ) : CustomAssetVerificationQueueEvent

    data class Cancelled(
        val task: CustomAssetVerificationTask,
    ) : CustomAssetVerificationQueueEvent
}

class CustomAssetVerificationQueue internal constructor(
    maximumQueuedTasks: Int,
    private val verifier: (CustomAssetVerificationTask) -> CustomAssetVerificationCompletion,
) : AutoCloseable {
    constructor(maximumQueuedTasks: Int = DEFAULT_MAXIMUM_QUEUED_TASKS) : this(
        maximumQueuedTasks,
        CustomAssetVerificationTask::verify,
    )

    private val events = ConcurrentLinkedQueue<CustomAssetVerificationQueueEvent>()
    private val logger = System.getLogger(CustomAssetVerificationQueue::class.java.name)
    private val outstanding: Semaphore
    private val executor: ThreadPoolExecutor

    init {
        require(maximumQueuedTasks > 0) {
            "Maximum queued custom asset verification tasks must be positive: $maximumQueuedTasks"
        }
        outstanding = Semaphore(maximumQueuedTasks + 1, true)
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(maximumQueuedTasks),
            VerificationThreadFactory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    fun trySubmit(task: CustomAssetVerificationTask): Boolean {
        if (!outstanding.tryAcquire()) {
            return false
        }
        return try {
            executor.execute(VerificationJob(task))
            true
        } catch (_: RejectedExecutionException) {
            outstanding.release()
            false
        }
    }

    fun pollEvent(): CustomAssetVerificationQueueEvent? {
        val event = events.poll() ?: return null
        outstanding.release()
        return event
    }

    fun drainEvents(): List<CustomAssetVerificationQueueEvent> = buildList {
        while (true) {
            add(pollEvent() ?: break)
        }
    }

    override fun close() {
        executor.shutdownNow().forEach { abandoned ->
            val job = abandoned as VerificationJob
            events += CustomAssetVerificationQueueEvent.Cancelled(job.task)
        }
        try {
            check(executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Custom asset verification worker did not stop within $SHUTDOWN_TIMEOUT_SECONDS seconds"
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while stopping custom asset verification worker", exception)
        }
    }

    private inner class VerificationJob(
        val task: CustomAssetVerificationTask,
    ) : Runnable {
        override fun run() {
            val completion = try {
                verifier(task)
            } catch (exception: Exception) {
                task.recoverFailure(exception)
            }
            if (completion == null) {
                events += CustomAssetVerificationQueueEvent.Cancelled(task)
                return
            }
            completion.failure?.let { failure ->
                logger.log(
                    System.Logger.Level.ERROR,
                    "Custom asset verification failed for ${task.customEmojiId}",
                    failure,
                )
            }
            events += CustomAssetVerificationQueueEvent.Completed(completion)
        }
    }

    private object VerificationThreadFactory : ThreadFactory {
        override fun newThread(task: Runnable): Thread = Thread(task, THREAD_NAME).apply {
            isDaemon = true
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_QUEUED_TASKS = 8
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val THREAD_NAME = "emotify-custom-asset-verifier"
    }
}

internal class SessionCustomAssetVerificationTicket(
    val owner: Any,
    val generation: Long,
    val lease: CustomAssetIngressBudget.Lease,
    val assembly: CustomEmojiEncodedAssembly,
)

internal sealed interface SessionCustomAssetUploadPreparation {
    data object Pending : SessionCustomAssetUploadPreparation

    data class VerificationRequired(
        val ticket: SessionCustomAssetVerificationTicket,
    ) : SessionCustomAssetUploadPreparation

    data class Rejected(
        val reason: CustomAssetUploadRejection,
    ) : SessionCustomAssetUploadPreparation
}

internal sealed interface SessionCustomAssetUploadCommit {
    data class Accepted(
        val deferredSelection: CustomEmotionSelection?,
    ) : SessionCustomAssetUploadCommit

    data class Rejected(
        val reason: CustomAssetUploadRejection,
        val selectionReason: me.whish.emotify.domain.SelectionRejectionReason,
        val deferredSelection: CustomEmotionSelection?,
    ) : SessionCustomAssetUploadCommit

    data object Stale : SessionCustomAssetUploadCommit
}
