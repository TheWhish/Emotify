package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker

@Suppress("unused")
class RemoteCustomAssetDecodePipelineTest : FunSpec({
    test("verified assets complete through the bounded worker") {
        val results = LinkedBlockingQueue<RemoteCustomAssetDecodeResult<me.whish.emotify.wire.v1.CustomEmojiAssetAssembly>>()
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, _, result -> results.add(result) },
            preparer = { it },
            preparedDisposer = {},
        )
        val asset = asset()

        try {
            pipeline.begin(1L)
            CustomEmojiAssetChunker.split(asset).forEach { chunk ->
                pipeline.submit(1L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }

            val result = results.poll(5, TimeUnit.SECONDS)
                .shouldBeInstanceOf<RemoteCustomAssetDecodeResult.Prepared<*>>()
            result.value.shouldBeInstanceOf<me.whish.emotify.wire.v1.CustomEmojiAssetAssembly>().asset shouldBe asset
        } finally {
            pipeline.close()
        }
    }

    test("disconnect discards a verification completed by a stale generation") {
        val verificationStarted = CountDownLatch(1)
        val allowVerification = CountDownLatch(1)
        val results = LinkedBlockingQueue<RemoteCustomAssetDecodeResult<me.whish.emotify.wire.v1.CustomEmojiAssetAssembly>>()
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, _, result -> results.add(result) },
            preparer = { it },
            preparedDisposer = {},
            verifier = { assembly ->
                verificationStarted.countDown()
                allowVerification.await(5, TimeUnit.SECONDS)
                assembly.tryVerify()
            },
        )

        try {
            pipeline.begin(4L)
            CustomEmojiAssetChunker.split(asset()).forEach { chunk ->
                pipeline.submit(4L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }
            verificationStarted.await(5, TimeUnit.SECONDS) shouldBe true

            pipeline.disconnect(4L)
            allowVerification.countDown()

            results.poll(250, TimeUnit.MILLISECONDS) shouldBe null
        } finally {
            allowVerification.countDown()
            pipeline.close()
        }
    }

    test("byte admission rejects work that cannot fit the bounded queue") {
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, _, _ -> },
            preparer = { it },
            preparedDisposer = {},
            maximumQueuedBytes = 1,
        )

        try {
            pipeline.begin(7L)
            val chunk = CustomEmojiAssetChunker.split(asset()).single()

            pipeline.submit(7L, chunk) shouldBe RemoteCustomAssetAdmission.SATURATED
            pipeline.submit(8L, chunk) shouldBe RemoteCustomAssetAdmission.INACTIVE_CONNECTION
        } finally {
            pipeline.close()
        }
    }

    test("closed pipeline refuses further work") {
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, _, _ -> },
            preparer = { it },
            preparedDisposer = {},
        )
        pipeline.begin(9L)
        pipeline.close()

        pipeline.submit(9L, CustomEmojiAssetChunker.split(asset()).single()) shouldBe
            RemoteCustomAssetAdmission.CLOSED
    }

    test("tracks multiple awaited assets without replacing the earlier identity") {
        val verificationStarted = CountDownLatch(1)
        val allowVerification = CountDownLatch(1)
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, _, _ -> },
            preparer = { it },
            preparedDisposer = {},
            verifier = { assembly ->
                verificationStarted.countDown()
                allowVerification.await(5, TimeUnit.SECONDS)
                assembly.tryVerify()
            },
        )
        val first = asset(1)
        val second = largeAsset(2)

        try {
            pipeline.begin(10L)
            CustomEmojiAssetChunker.split(first).forEach { chunk ->
                pipeline.submit(10L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }
            verificationStarted.await(5, TimeUnit.SECONDS) shouldBe true
            pipeline.submit(10L, CustomEmojiAssetChunker.split(second).first()) shouldBe
                RemoteCustomAssetAdmission.ACCEPTED

            pipeline.isAwaiting(10L, first.id) shouldBe true
            pipeline.isAwaiting(10L, second.id) shouldBe true
        } finally {
            allowVerification.countDown()
            pipeline.close()
        }
    }

    test("expires an incomplete assembly and resets the worker before the next transfer") {
        val time = FakeMonotonicTimeSource()
        val results = LinkedBlockingQueue<Pair<me.whish.emotify.domain.CustomEmojiId, RemoteCustomAssetDecodeResult<me.whish.emotify.wire.v1.CustomEmojiAssetAssembly>>>()
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = Runnable::run,
            completionListener = { _, id, result -> results.add(id to result) },
            preparer = { it },
            preparedDisposer = {},
            awaitTimeoutMillis = 100L,
            timeSource = time,
        )
        val incomplete = largeAsset(3)
        val chunks = CustomEmojiAssetChunker.split(incomplete)
        val next = asset(4)

        try {
            (chunks.size > 1) shouldBe true
            pipeline.begin(11L)
            pipeline.submit(11L, chunks.first()) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            pipeline.isAwaiting(11L, incomplete.id) shouldBe true

            time.advanceBy(101.milliseconds)

            pipeline.isAwaiting(11L, incomplete.id) shouldBe false
            val abandoned = results.poll(5, TimeUnit.SECONDS).shouldNotBeNull()
            abandoned.first shouldBe incomplete.id
            abandoned.second shouldBe RemoteCustomAssetDecodeResult.Abandoned

            CustomEmojiAssetChunker.split(next).forEach { chunk ->
                pipeline.submit(11L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }
            results.poll(5, TimeUnit.SECONDS).shouldNotBeNull().second
                .shouldBeInstanceOf<RemoteCustomAssetDecodeResult.Prepared<*>>()
        } finally {
            pipeline.close()
        }
    }

    test("executor rejection disposes prepared data and leaves the worker operational") {
        val executions = AtomicInteger()
        val disposed = CountDownLatch(1)
        val results = LinkedBlockingQueue<RemoteCustomAssetDecodeResult<me.whish.emotify.wire.v1.CustomEmojiAssetAssembly>>()
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = { task ->
                if (executions.getAndIncrement() == 0) {
                    throw IllegalStateException("rejected")
                }
                task.run()
            },
            completionListener = { _, _, result -> results.add(result) },
            preparer = { it },
            preparedDisposer = { disposed.countDown() },
        )
        val rejected = asset(5)
        val accepted = asset(6)

        try {
            pipeline.begin(12L)
            CustomEmojiAssetChunker.split(rejected).forEach { chunk ->
                pipeline.submit(12L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }
            disposed.await(5, TimeUnit.SECONDS) shouldBe true
            pipeline.isAwaiting(12L, rejected.id) shouldBe false

            CustomEmojiAssetChunker.split(accepted).forEach { chunk ->
                pipeline.submit(12L, chunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            }
            results.poll(5, TimeUnit.SECONDS)
                .shouldBeInstanceOf<RemoteCustomAssetDecodeResult.Prepared<*>>()
        } finally {
            pipeline.close()
        }
    }

    test("stale abandoned completion does not affect a replacement transfer with the same id") {
        val time = FakeMonotonicTimeSource()
        val delayedCompletions = LinkedBlockingQueue<Runnable>()
        val abandonedNotifications = AtomicInteger()
        val pipeline = RemoteCustomAssetDecodePipeline(
            completionExecutor = { task -> delayedCompletions.add(task) },
            completionListener = { _, _, result ->
                if (result is RemoteCustomAssetDecodeResult.Abandoned) {
                    abandonedNotifications.incrementAndGet()
                }
            },
            preparer = { it },
            preparedDisposer = {},
            awaitTimeoutMillis = 100L,
            timeSource = time,
        )
        val asset = largeAsset(7)
        val firstChunk = CustomEmojiAssetChunker.split(asset).first()

        try {
            pipeline.begin(13L)
            pipeline.submit(13L, firstChunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            pipeline.isAwaiting(13L, asset.id) shouldBe true

            time.advanceBy(101.milliseconds)

            pipeline.isAwaiting(13L, asset.id) shouldBe false
            pipeline.submit(13L, firstChunk) shouldBe RemoteCustomAssetAdmission.ACCEPTED
            pipeline.isAwaiting(13L, asset.id) shouldBe true

            checkNotNull(delayedCompletions.poll(5, TimeUnit.SECONDS)).run()

            abandonedNotifications.get() shouldBe 0
            pipeline.isAwaiting(13L, asset.id) shouldBe true
        } finally {
            pipeline.close()
        }
    }
}) {
    companion object {
        private fun asset(seed: Int = 0): CustomEmojiAsset = CustomEmojiAsset.create(
            CustomEmojiPixels.of(16, IntArray(16 * 16) { index ->
                0xFF000000.toInt() or (index + seed * 257)
            }),
        )

        private fun largeAsset(seed: Int): CustomEmojiAsset {
            val random = java.util.Random(seed.toLong())
            return CustomEmojiAsset.create(
                CustomEmojiPixels.of(128, IntArray(128 * 128) { random.nextInt() }),
            )
        }
    }
}
