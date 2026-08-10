package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker

@Suppress("unused")
class CustomAssetUploadPipelineTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
    val hello = ClientHello(capabilities)
    val serverHello = ServerHello(capabilities, TEST_SERVER_HELLO.cooldownMillis, TEST_CATALOG)

    fun largeAsset(seed: Int): CustomEmojiAsset = CustomEmojiAsset.create(
        CustomEmojiPixels.of(128, IntArray(128 * 128) { index -> seed xor index }),
    )

    fun upload(
        engine: EmotifyServerEngine,
        connection: ConnectionKey,
        asset: CustomEmojiAsset,
    ): CustomAssetVerificationTask {
        val chunks = CustomEmojiAssetChunker.split(asset)
        chunks.dropLast(1).forEach { chunk ->
            engine.prepareCustomAssetChunk(connection, chunk, permittedToUpload = true) shouldBe
                CustomAssetUploadPreparation.Pending
        }
        return engine.prepareCustomAssetChunk(connection, chunks.last(), permittedToUpload = true)
            .shouldBeInstanceOf<CustomAssetUploadPreparation.VerificationRequired>()
            .task
    }

    test("permission denial happens before custom asset memory admission") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)

        val result = harness.engine.prepareCustomAssetChunk(
            source,
            CustomEmojiAssetChunker.split(largeAsset(1)).first(),
            permittedToUpload = false,
        )

        result shouldBe CustomAssetUploadPreparation.Rejected(CustomAssetUploadRejection.PERMISSION_DENIED)
        budget.retainedBytes() shouldBe 0L
    }

    test("selection for the asset being verified is resumed after owner-thread commit") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val asset = largeAsset(2)
        val task = upload(harness.engine, source, asset)

        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null)) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.CUSTOM_ASSET_VERIFYING)

        val commit = harness.engine.completeCustomAssetVerification(task.verify(), testPlayer(source))
            .shouldBeInstanceOf<CustomAssetUploadCommit.Accepted>()

        commit.resumedSelection.shouldBeInstanceOf<ServerSelectionResult.Published>()
        harness.transport.customPlays.size shouldBe 1
        budget.retainedBytes() shouldBe 0L
    }

    test("permission is rechecked before verified bytes enter the shared asset store") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val asset = largeAsset(3)
        val task = upload(harness.engine, source, asset)
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null)) shouldBe
            ServerSelectionResult.Ignored(SelectionIgnoreReason.CUSTOM_ASSET_VERIFYING)

        val deniedPlayer = testPlayer(source).copy(permittedToPublish = false)
        val commit = harness.engine.completeCustomAssetVerification(task.verify(), deniedPlayer)
            .shouldBeInstanceOf<CustomAssetUploadCommit.Rejected>()

        commit.reason shouldBe CustomAssetUploadRejection.PERMISSION_DENIED
        commit.resumedSelection.shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.PLAYER_STATE
        budget.retainedBytes() shouldBe 0L
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_ASSET_MISSING
    }

    test("policy is rechecked before verified bytes enter the shared asset store") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val asset = largeAsset(30)
        val task = upload(harness.engine, source, asset)

        harness.engine.replacePolicy(TEST_ENABLED_POLICY.copy(customEmojisEnabled = false))
        val commit = harness.engine.completeCustomAssetVerification(task.verify(), testPlayer(source))
            .shouldBeInstanceOf<CustomAssetUploadCommit.Rejected>()

        commit.reason shouldBe CustomAssetUploadRejection.CUSTOM_EMOJIS_DISABLED
        budget.retainedBytes() shouldBe 0L
        harness.engine.replacePolicy(TEST_ENABLED_POLICY)
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_ASSET_MISSING
    }

    test("reconnect makes an old verification completion stale without authorizing its asset") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val playerId = UUID(7L, 7L)
        val first = testConnection(1L, playerId)
        val replacement = testConnection(2L, playerId)
        harness.openSupported(first, hello)
        val asset = largeAsset(4)
        val task = upload(harness.engine, first, asset)

        harness.openSupported(replacement, hello)
        harness.engine.completeCustomAssetVerification(task.verify(), testPlayer(replacement)) shouldBe
            CustomAssetUploadCommit.Stale

        budget.retainedBytes() shouldBe 0L
        harness.engine.selectCustom(testPlayer(replacement), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_ASSET_MISSING
    }

    test("completion with a different player snapshot is stale and releases its owner lease") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        val different = testConnection(2L)
        harness.openSupported(source, hello)
        harness.openSupported(different, hello)
        val asset = largeAsset(40)
        val task = upload(harness.engine, source, asset)

        harness.engine.completeCustomAssetVerification(task.verify(), testPlayer(different)) shouldBe
            CustomAssetUploadCommit.Stale

        budget.retainedBytes() shouldBe 0L
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_ASSET_MISSING
    }

    test("disconnect releases verification memory before worker completion") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val task = upload(harness.engine, source, largeAsset(41))

        (budget.retainedBytes() > 0L) shouldBe true
        harness.engine.close(source) shouldBe ServerCloseResult.CLOSED
        budget.retainedBytes() shouldBe 0L
        harness.engine.completeCustomAssetVerification(task.verify(), testPlayer(source)) shouldBe
            CustomAssetUploadCommit.Stale
    }

    test("cancelled generation cannot commit over a later upload") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val firstAsset = largeAsset(5)
        val secondAsset = largeAsset(6)
        val firstTask = upload(harness.engine, source, firstAsset)

        harness.engine.cancelCustomAssetVerification(firstTask, CustomAssetUploadRejection.QUEUE_SATURATED) shouldBe
            CustomAssetUploadCancellation.Cancelled
        val secondTask = upload(harness.engine, source, secondAsset)
        val retainedForSecond = budget.retainedBytes()

        val staleCompletion = CustomAssetVerificationCompletion(
            firstTask,
            firstTask.ticket.assembly.tryVerify(),
        )
        harness.engine.completeCustomAssetVerification(staleCompletion, testPlayer(source)) shouldBe
            CustomAssetUploadCommit.Stale
        budget.retainedBytes() shouldBe retainedForSecond
        harness.engine.completeCustomAssetVerification(secondTask.verify(), testPlayer(source))
            .shouldBeInstanceOf<CustomAssetUploadCommit.Accepted>()
        budget.retainedBytes() shouldBe 0L
    }

    test("unexpected worker failure becomes a rejected completion and releases its lease") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val task = upload(harness.engine, source, largeAsset(42))
        val verificationFinished = CountDownLatch(1)
        val queue = CustomAssetVerificationQueue(maximumQueuedTasks = 1) { queued ->
            queued.verify()
            verificationFinished.countDown()
            throw IllegalStateException("unexpected verification failure")
        }

        queue.trySubmit(task) shouldBe true
        check(verificationFinished.await(5, TimeUnit.SECONDS))
        queue.close()
        val event = queue.pollEvent().shouldBeInstanceOf<CustomAssetVerificationQueueEvent.Completed>()
        val commit = harness.engine.completeCustomAssetVerification(event.completion, testPlayer(source))
            .shouldBeInstanceOf<CustomAssetUploadCommit.Rejected>()

        commit.reason shouldBe CustomAssetUploadRejection.VERIFICATION_FAILED
        budget.retainedBytes() shouldBe 0L
        queue.pollEvent() shouldBe null
    }

    test("bounded verification queue rejects saturation and every ticket can release its lease") {
        val budget = CustomAssetIngressBudget()
        val harness = engineHarness(
            serverHello = serverHello,
            featureRegistry = EmotifyProtocolFeatures.registry,
            customAssetIngressBudget = budget,
        )
        val connections = (1L..3L).map(::testConnection)
        connections.forEach { connection -> harness.openSupported(connection, hello) }
        val tasks = connections.mapIndexed { index, connection ->
            upload(harness.engine, connection, largeAsset(index + 10))
        }
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val queue = CustomAssetVerificationQueue(maximumQueuedTasks = 1) { task ->
            if (firstStarted.count == 1L) {
                firstStarted.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            task.verify().also { completed.countDown() }
        }

        queue.trySubmit(tasks[0]) shouldBe true
        check(firstStarted.await(5, TimeUnit.SECONDS))
        queue.trySubmit(tasks[1]) shouldBe true
        queue.trySubmit(tasks[2]) shouldBe false
        harness.engine.cancelCustomAssetVerification(
            tasks[2],
            CustomAssetUploadRejection.QUEUE_SATURATED,
        ) shouldBe CustomAssetUploadCancellation.Cancelled

        releaseFirst.countDown()
        check(completed.await(5, TimeUnit.SECONDS))
        queue.close()
        queue.drainEvents().forEach { event ->
            when (event) {
                is CustomAssetVerificationQueueEvent.Completed -> {
                    val player = testPlayer(event.completion.task.connection)
                    harness.engine.completeCustomAssetVerification(event.completion, player)
                }
                is CustomAssetVerificationQueueEvent.Cancelled ->
                    harness.engine.cancelCustomAssetVerification(
                        event.task,
                        CustomAssetUploadRejection.QUEUE_SATURATED,
                    )
            }
        }

        budget.retainedBytes() shouldBe 0L
    }
})
