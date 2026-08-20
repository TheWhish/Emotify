package me.whish.emotify.paper.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.ArrayDeque
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.OutboundDeliveryStatus
import me.whish.emotify.server.core.ServerHelloRefreshPlan
import org.bukkit.plugin.java.JavaPlugin

data class PaperPolicyRefreshBatchResult(
    val attemptedSessions: Int,
    val sentSessions: Int,
    val unavailableSessions: Int,
    val failedSessions: Int,
    val remainingSessions: Int,
    val firstFailure: RuntimeException?,
)

class PaperPolicyRefreshQueue(
    private val maximumBatchSize: Int = DEFAULT_MAXIMUM_BATCH_SIZE,
) {
    private val pending = ArrayDeque<ConnectionKey>()
    private var plan: ServerHelloRefreshPlan? = null

    init {
        require(maximumBatchSize > 0) {
            "Maximum Paper policy refresh batch size must be positive: $maximumBatchSize"
        }
    }

    val size: Int
        get() = pending.size

    fun replace(connections: Collection<ConnectionKey>, plan: ServerHelloRefreshPlan): Int {
        pending.clear()
        this.plan = plan
        val distinct = HashSet<ConnectionKey>(connections.size)
        for (connection in connections) {
            if (distinct.add(connection)) {
                pending.addLast(connection)
            }
        }
        return pending.size
    }

    fun drain(): PaperPolicyRefreshBatchResult {
        val activePlan = plan ?: return PaperPolicyRefreshBatchResult(0, 0, 0, 0, 0, null)
        var attempted = 0
        var sent = 0
        var unavailable = 0
        var failed = 0
        var firstFailure: RuntimeException? = null

        while (attempted < maximumBatchSize && pending.isNotEmpty()) {
            val connection = pending.removeFirst()
            attempted++
            val result = activePlan.send(connection)
            when (result.status) {
                OutboundDeliveryStatus.SENT -> sent++
                OutboundDeliveryStatus.UNAVAILABLE -> unavailable++
                OutboundDeliveryStatus.FAILED -> {
                    failed++
                    if (firstFailure == null && result.failure != null) {
                        firstFailure = result.failure
                    }
                }
            }
        }

        if (pending.isEmpty()) {
            plan = null
        }

        return PaperPolicyRefreshBatchResult(
            attemptedSessions = attempted,
            sentSessions = sent,
            unavailableSessions = unavailable,
            failedSessions = failed,
            remainingSessions = pending.size,
            firstFailure = firstFailure,
        )
    }

    fun clear(): Int {
        val remaining = pending.size
        pending.clear()
        plan = null
        return remaining
    }

    companion object {
        const val DEFAULT_MAXIMUM_BATCH_SIZE = 64
    }
}

class PaperPolicyRefreshDispatcher(
    private val plugin: JavaPlugin,
    private val queue: PaperPolicyRefreshQueue = PaperPolicyRefreshQueue(),
    private val reportBatch: (PaperPolicyRefreshBatchResult) -> Unit,
) {
    private var started = false
    private var scheduledTask: ScheduledTask? = null

    fun start() {
        check(PaperGlobalTasks.isGlobalThread(plugin.server)) { "Policy refresh dispatcher must start on the global server thread" }
        check(!started) { "Policy refresh dispatcher has already started" }
        started = true
    }

    fun replace(connections: Collection<ConnectionKey>, plan: ServerHelloRefreshPlan): Int {
        check(PaperGlobalTasks.isGlobalThread(plugin.server)) { "Policy refresh queue must be replaced on the global server thread" }
        check(started) { "Policy refresh dispatcher has not started" }
        val queued = queue.replace(connections, plan)
        try {
            if (queued > 0) {
                ensureScheduled()
            } else {
                cancelScheduledTask()
            }
        } catch (failure: RuntimeException) {
            queue.clear()
            cancelScheduledTask()
            throw failure
        }
        return queued
    }

    fun clear(): Int {
        check(PaperGlobalTasks.isGlobalThread(plugin.server)) { "Policy refresh queue must be cleared on the global server thread" }
        cancelScheduledTask()
        started = false
        return queue.clear()
    }

    private fun ensureScheduled() {
        if (scheduledTask != null) {
            return
        }
        scheduledTask = PaperGlobalTasks.repeating(plugin, Runnable(::drain), 1L)
    }

    private fun drain() {
        try {
            val result = queue.drain()
            if (result.attemptedSessions > 0) {
                reportBatch(result)
            }
            if (result.remainingSessions == 0) {
                cancelScheduledTask()
            }
        } catch (error: Throwable) {
            cancelScheduledTask()
            throw error
        }
    }

    private fun cancelScheduledTask() {
        scheduledTask?.cancel()
        scheduledTask = null
    }
}
