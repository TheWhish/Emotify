package me.whish.emotify.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

@Suppress("unused")
class NetworkMainThreadDispatcherTest : FunSpec({
    test("asynchronous success runs task and completion once") {
        val future = CompletableFuture<Void>()
        val taskCalls = AtomicInteger()
        val completionCalls = AtomicInteger()
        lateinit var scheduled: Runnable

        NetworkMainThreadDispatcher.submit(
            enqueue = { task ->
                scheduled = task
                future
            },
            task = { taskCalls.incrementAndGet() },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = { error("Unexpected enqueue failure") },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        taskCalls.get() shouldBe 0
        completionCalls.get() shouldBe 0
        scheduled.run()
        future.complete(null)

        taskCalls.get() shouldBe 1
        completionCalls.get() shouldBe 1
    }

    test("synchronous completed future runs completion once") {
        val taskCalls = AtomicInteger()
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { task ->
                task.run()
                CompletableFuture.completedFuture(null)
            },
            task = { taskCalls.incrementAndGet() },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = { error("Unexpected enqueue failure") },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        taskCalls.get() shouldBe 1
        completionCalls.get() shouldBe 1
    }

    test("runtime task failure is handled without exceptional completion") {
        val future = CompletableFuture<Void>()
        val expected = IllegalStateException("task failed")
        var observed: RuntimeException? = null
        val completionCalls = AtomicInteger()
        lateinit var scheduled: Runnable

        NetworkMainThreadDispatcher.submit(
            enqueue = { task ->
                scheduled = task
                future
            },
            task = { throw expected },
            onTaskFailure = { observed = it },
            onEnqueueFailure = { error("Unexpected enqueue failure") },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        scheduled.run()
        future.complete(null)

        observed shouldBe expected
        future.isCompletedExceptionally shouldBe false
        completionCalls.get() shouldBe 1
    }

    test("synchronous enqueue failure is handled and completes cleanup") {
        val expected = IllegalStateException("enqueue failed")
        var observed: Throwable? = null
        val taskCalls = AtomicInteger()
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { throw expected },
            task = { taskCalls.incrementAndGet() },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = { observed = it },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        observed shouldBe expected
        taskCalls.get() shouldBe 0
        completionCalls.get() shouldBe 1
    }

    test("future cancellation before task execution completes cleanup") {
        val future = CompletableFuture<Void>()
        val taskCalls = AtomicInteger()
        val enqueueFailureCalls = AtomicInteger()
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { future },
            task = { taskCalls.incrementAndGet() },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = { enqueueFailureCalls.incrementAndGet() },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        future.cancel(false)

        taskCalls.get() shouldBe 0
        enqueueFailureCalls.get() shouldBe 0
        completionCalls.get() shouldBe 1
    }

    test("exceptional future before task execution reports enqueue failure and completes cleanup once") {
        val future = CompletableFuture<Void>()
        val expected = IllegalStateException("asynchronous enqueue failed")
        var observed: Throwable? = null
        val taskCalls = AtomicInteger()
        val failureCalls = AtomicInteger()
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { future },
            task = { taskCalls.incrementAndGet() },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = {
                observed = it
                failureCalls.incrementAndGet()
            },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        future.completeExceptionally(expected)

        observed shouldBe expected
        taskCalls.get() shouldBe 0
        failureCalls.get() shouldBe 1
        completionCalls.get() shouldBe 1
    }

    test("an exceptional future after task start does not duplicate task diagnostics") {
        val future = CompletableFuture<Void>()
        val expected = IllegalStateException("task failed")
        val taskFailureCalls = AtomicInteger()
        val enqueueFailureCalls = AtomicInteger()
        val completionCalls = AtomicInteger()
        lateinit var scheduled: Runnable

        NetworkMainThreadDispatcher.submit(
            enqueue = { task ->
                scheduled = task
                future
            },
            task = { throw expected },
            onTaskFailure = {
                it shouldBe expected
                taskFailureCalls.incrementAndGet()
            },
            onEnqueueFailure = { enqueueFailureCalls.incrementAndGet() },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        scheduled.run()
        future.completeExceptionally(expected)

        taskFailureCalls.get() shouldBe 1
        enqueueFailureCalls.get() shouldBe 0
        completionCalls.get() shouldBe 1
    }

    test("cleanup runs once when asynchronous enqueue diagnostics fail") {
        val expected = IllegalStateException("asynchronous enqueue failed")
        val failureCalls = AtomicInteger()
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { CompletableFuture.failedFuture(expected) },
            task = { error("Unexpected task execution") },
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = {
                failureCalls.incrementAndGet()
                throw IllegalStateException("diagnostic failed")
            },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        failureCalls.get() shouldBe 1
        completionCalls.get() shouldBe 1
    }

    test("guarded asynchronous fatal failure completes cleanup") {
        val submitted = CompletableFuture<Void>()
        val guarded = submitted.exceptionally { null }
        val completionCalls = AtomicInteger()

        NetworkMainThreadDispatcher.submit(
            enqueue = { guarded },
            task = {},
            onTaskFailure = { error("Unexpected task failure") },
            onEnqueueFailure = { error("Unexpected enqueue failure") },
            onCompletion = { completionCalls.incrementAndGet() },
        )

        submitted.completeExceptionally(AssertionError("fatal"))

        completionCalls.get() shouldBe 1
    }

    test("synchronous fatal failure completes cleanup and propagates") {
        val expected = AssertionError("fatal")
        val completionCalls = AtomicInteger()

        val thrown = shouldThrow<AssertionError> {
            NetworkMainThreadDispatcher.submit(
                enqueue = { task ->
                    task.run()
                    CompletableFuture.completedFuture(null)
                },
                task = { throw expected },
                onTaskFailure = { error("Unexpected task failure") },
                onEnqueueFailure = { error("Unexpected enqueue failure") },
                onCompletion = { completionCalls.incrementAndGet() },
            )
        }

        thrown shouldBe expected
        completionCalls.get() shouldBe 1
    }
})
