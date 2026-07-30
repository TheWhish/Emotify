package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import java.util.concurrent.Executor

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
        )

        store.submit("broken")
        executor.runAll()
        store.submit("recovered")
        executor.runAll()

        attempts.shouldContainExactly("broken", "recovered")
        failures.map(Throwable::message).shouldContainExactly("read-only")
        store.load() shouldBe "recovered"
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
