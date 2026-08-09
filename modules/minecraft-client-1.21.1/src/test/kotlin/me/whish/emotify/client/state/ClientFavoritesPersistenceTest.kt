package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@Suppress("unused")
class ClientFavoritesPersistenceTest : FunSpec({
    test("queued snapshots coalesce to the latest immutable view") {
        val executor = ManualExecutor()
        val writes = mutableListOf<String>()
        var loads = 0
        val store = SerializedSnapshotStore(
            loader = {
                loads++
                "disk"
            },
            executor = executor,
            sink = writes::add,
            onFailure = { error -> throw error },
        )

        store.load() shouldBe "disk"
        store.submit("first")
        store.submit("second")
        store.submit("latest")

        store.load() shouldBe "latest"
        loads shouldBeExactly 1
        executor.pendingTasks shouldBeExactly 1

        executor.runAll()

        writes.shouldContainExactly("latest")
    }

    test("snapshot submitted during a write becomes the final persisted value") {
        val executor = ManualExecutor()
        val writes = mutableListOf<String>()
        lateinit var store: SerializedSnapshotStore<String>
        store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = { snapshot ->
                writes += snapshot
                if (snapshot == "first") {
                    store.submit("middle")
                    store.submit("latest")
                }
            },
            onFailure = { error -> throw error },
        )

        store.submit("first")
        executor.runAll()

        writes.shouldContainExactly("first", "latest")
        store.load() shouldBe "latest"
    }

    test("sink failure is reported and does not poison later submissions") {
        val executor = ManualExecutor()
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = { snapshot ->
                attempts += snapshot
                if (snapshot == "broken") {
                    throw IllegalStateException("read-only")
                }
            },
            onFailure = failures::add,
            retryDelayMillis = 0L,
        )

        store.submit("broken")
        executor.runAll()
        store.submit("recovered")
        executor.runAll()

        attempts.shouldContainExactly("broken", "broken", "broken", "recovered")
        failures.map(Throwable::message).shouldContainExactly("read-only", "read-only", "read-only")
        store.load() shouldBe "recovered"
    }

    test("a transient sink failure retries and persists the latest snapshot") {
        val executor = ManualExecutor()
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        var fail = true
        val store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = { snapshot ->
                attempts += snapshot
                if (fail) {
                    fail = false
                    throw IllegalStateException("temporary")
                }
            },
            onFailure = failures::add,
            retryDelayMillis = 0L,
        )

        store.submit("latest")
        executor.runAll()

        attempts.shouldContainExactly("latest", "latest")
        failures.map(Throwable::message).shouldContainExactly("temporary")
        store.flush(0, TimeUnit.NANOSECONDS) shouldBe true
    }

    test("a newer snapshot supersedes a failed write without retrying stale data") {
        val executor = ManualExecutor()
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        lateinit var store: SerializedSnapshotStore<String>
        store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = { snapshot ->
                attempts += snapshot
                if (snapshot == "first") {
                    store.submit("latest")
                    throw IllegalStateException("stale")
                }
            },
            onFailure = failures::add,
            retryDelayMillis = 0L,
        )

        store.submit("first")
        executor.runAll()

        attempts.shouldContainExactly("first", "latest")
        failures.map(Throwable::message).shouldContainExactly("stale")
        store.flush(0, TimeUnit.NANOSECONDS) shouldBe true
    }

    test("a permanent sink failure stops after the bounded attempts and remains unflushed") {
        val executor = ManualExecutor()
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = { snapshot ->
                attempts += snapshot
                throw IllegalStateException("permanent")
            },
            onFailure = failures::add,
            maximumWriteAttempts = 3,
            retryDelayMillis = 0L,
        )

        store.submit("latest")
        executor.runAll()

        attempts.shouldContainExactly("latest", "latest", "latest")
        failures.size shouldBeExactly 3
        executor.pendingTasks shouldBeExactly 0
        store.flush(0, TimeUnit.NANOSECONDS) shouldBe false
    }

    test("flush reports pending work and completes after the writer becomes idle") {
        val executor = ManualExecutor()
        val store = SerializedSnapshotStore(
            loader = { "disk" },
            executor = executor,
            sink = {},
            onFailure = { error -> throw error },
        )

        store.submit("latest")

        store.flush(0, TimeUnit.NANOSECONDS) shouldBe false
        executor.runAll()
        store.flush(0, TimeUnit.NANOSECONDS) shouldBe true
    }

    test("atomic updates compose against the latest in-memory snapshot") {
        val executor = ManualExecutor()
        val writes = mutableListOf<String>()
        val store = SerializedSnapshotStore(
            loader = { "base" },
            executor = executor,
            sink = writes::add,
            onFailure = { error -> throw error },
        )

        store.update { current -> "$current-settings" }
        store.update { current -> "$current-favorites" }
        executor.runAll()

        store.load() shouldBe "base-settings-favorites"
        writes.shouldContainExactly("base-settings-favorites")
    }

    test("read only updates change memory without scheduling persistence") {
        val executor = ManualExecutor()
        val writes = mutableListOf<String>()
        val store = SerializedSnapshotStore(
            loader = { "future-schema-defaults" },
            executor = executor,
            sink = writes::add,
            onFailure = { error -> throw error },
        )

        store.updateInMemory { current -> "$current-session-change" }

        store.load() shouldBe "future-schema-defaults-session-change"
        executor.pendingTasks shouldBeExactly 0
        writes shouldBe emptyList()
    }

    test("failure log gate admits immediately and rate limits repeated failures") {
        val gate = FailureLogGate(100)

        gate.tryAcquire(1_000) shouldBe true
        gate.tryAcquire(1_099) shouldBe false
        gate.tryAcquire(1_100) shouldBe true
        gate.tryAcquire(50) shouldBe true
    }
})

private class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val pendingTasks: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
        }
    }
}
