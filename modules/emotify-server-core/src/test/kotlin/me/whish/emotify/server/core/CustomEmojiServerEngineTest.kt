package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.types.shouldBeInstanceOf
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec
import kotlin.time.Duration.Companion.milliseconds
import java.util.SplittableRandom

@Suppress("unused")
class CustomEmojiServerEngineTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
    val hello = ClientHello(capabilities)
    val serverHello = ServerHello(capabilities, TEST_SERVER_HELLO.cooldownMillis, TEST_CATALOG)
    val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { index -> if (index % 2 == 0) 0xFFFFFFFF.toInt() else 0 }))
    val animatedAsset = CustomEmojiAsset.create(
        listOf(
            CustomEmojiFrame(asset.pixels, 67),
            CustomEmojiFrame(CustomEmojiPixels.of(IntArray(64) { it }), 133),
        ),
    )

    test("first custom selection transfers the asset once and later selections use its reference") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val recipient = testConnection(2L)
        harness.openSupported(source, hello)
        harness.openSupported(recipient, hello)
        harness.audiencePort.delegate = candidateAudiencePort(listOf(AudienceCandidateFixture(recipient)))

        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, asset))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()
        harness.time.advanceBy(TEST_SERVER_HELLO.cooldownMillis.milliseconds)
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        harness.transport.customAssets.size shouldBe 1
        harness.transport.customAssets.single().playerId shouldBe recipient.playerId
        harness.transport.customPlays.size shouldBe 4
    }

    test("unknown custom reference is rejected without publication") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        harness.openSupported(source, hello)

        val result = harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        result.reason shouldBe SelectionRejectionReason.CUSTOM_ASSET_MISSING
        harness.transport.customAssets.size shouldBe 0
        harness.transport.customPlays.size shouldBe 0
    }

    test("clients without the negotiated feature never receive custom assets or plays") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val legacy = testConnection(2L)
        harness.openSupported(source, hello)
        harness.openSupported(legacy, TEST_CLIENT_HELLO)
        harness.audiencePort.delegate = candidateAudiencePort(listOf(AudienceCandidateFixture(legacy)))

        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, asset))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        harness.transport.customAssets.size shouldBe 0
        harness.transport.customPlays.map { recorded -> recorded.playerId } shouldBe listOf(source.playerId)
    }

    test("disabled custom emojis are rejected without caching or broadcasting client data") {
        val disabledPolicy = TEST_ENABLED_POLICY.copy(customEmojisEnabled = false)
        val harness = engineHarness(
            serverHello = serverHello,
            policy = disabledPolicy,
            featureRegistry = EmotifyProtocolFeatures.registry,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)

        val result = harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(asset.id, asset))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        result.reason shouldBe SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED
        harness.transport.customAssets.size shouldBe 0
        harness.transport.customPlays.size shouldBe 0
    }

    test("disabled custom emojis reject lossless uploads before assembly") {
        val disabledPolicy = TEST_ENABLED_POLICY.copy(customEmojisEnabled = false)
        val harness = engineHarness(
            serverHello = serverHello,
            policy = disabledPolicy,
            featureRegistry = EmotifyProtocolFeatures.registry,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val largeAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))

        CustomEmojiAssetChunker.split(largeAsset).forEach { chunk ->
            harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe false
        }

        val result = harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(largeAsset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>()
        result.reason shouldBe SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED
    }

    test("lossless upload bytes are reused for fan out without server reencoding") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val recipient = testConnection(2L)
        harness.openSupported(source, hello)
        harness.openSupported(recipient, hello)
        harness.audiencePort.delegate = candidateAudiencePort(listOf(AudienceCandidateFixture(recipient)))
        val largeAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))
        val chunks = CustomEmojiAssetChunker.split(largeAsset)

        chunks.forEach { chunk ->
            harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe true
        }
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(largeAsset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        harness.transport.customAssets.single().losslessChunks shouldBe chunks
    }

    test("lossless upload of a legacy-size asset is broadcast through the legacy representation") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val recipient = testConnection(2L)
        harness.openSupported(source, hello)
        harness.openSupported(recipient, hello)
        harness.audiencePort.delegate = candidateAudiencePort(listOf(AudienceCandidateFixture(recipient)))
        val legacyAnimated = CustomEmojiAsset.create(
            listOf(
                CustomEmojiFrame(CustomEmojiPixels.of(16, IntArray(16 * 16) { it }), 100),
                CustomEmojiFrame(CustomEmojiPixels.of(16, IntArray(16 * 16) { it + 1 }), 100),
            ),
        )

        CustomEmojiAssetChunker.split(legacyAnimated).forEach { chunk ->
            harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe true
        }
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(legacyAnimated.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        harness.transport.customAssets.single().losslessChunks shouldBe null
    }

    test("large inline assets cannot bypass the verified lossless transfer path") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val largeAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))

        val result = harness.engine.selectCustom(
            testPlayer(source),
            CustomEmotionSelection(largeAsset.id, largeAsset),
        ).shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        result.reason shouldBe SelectionRejectionReason.CUSTOM_ASSET_MISSING
        harness.transport.customAssets shouldBe emptyList()
    }

    test("policy expansion clears stale custom asset rejection state without reconnect") {
        val restricted = TEST_ENABLED_POLICY.copy(
            maximumStaticCustomEmojiSize = 32,
            maximumAnimatedCustomEmojiSize = 32,
        )
        val harness = engineHarness(
            serverHello = serverHello,
            policy = restricted,
            featureRegistry = EmotifyProtocolFeatures.registry,
        )
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val expandedAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(64, IntArray(64 * 64) { it }))
        val chunks = CustomEmojiAssetChunker.split(expandedAsset)

        chunks.forEachIndexed { index, chunk ->
            harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe (index != chunks.lastIndex)
        }
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(expandedAsset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE

        harness.engine.replacePolicy(TEST_ENABLED_POLICY)
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(expandedAsset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_ASSET_MISSING
        chunks.forEach { chunk -> harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe true }
        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(expandedAsset.id, null))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()
    }

    test("lossless upload budget admits a complete transfer atomically") {
        fun denseAnimatedAsset(seed: Int): CustomEmojiAsset {
            val random = SplittableRandom(seed.toLong())
            return CustomEmojiAsset.create(
                List(CustomEmojiAsset.MAXIMUM_FRAME_COUNT) {
                    CustomEmojiFrame(
                        CustomEmojiPixels.of(
                            CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE,
                            IntArray(CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE * CustomEmojiAsset.MAXIMUM_ANIMATED_SIZE) {
                                random.nextInt()
                            },
                        ),
                        CustomEmojiAsset.MINIMUM_FRAME_DURATION_MILLIS,
                    )
                },
            )
        }
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        harness.openSupported(source, hello)
        val first = denseAnimatedAsset(0x13579BDF)
        val second = denseAnimatedAsset(0x2468ACE)
        val firstChunks = CustomEmojiAssetChunker.split(first)
        val secondChunks = CustomEmojiAssetChunker.split(second)

        CustomEmojiLosslessCodec.encodedSize(first) shouldBeGreaterThan 480 * 1_024
        firstChunks.forEach { chunk -> harness.engine.receiveCustomAssetChunk(source, chunk) shouldBe true }
        harness.engine.receiveCustomAssetChunk(source, secondChunks.first()) shouldBe false
        harness.engine.receiveCustomAssetChunk(source, secondChunks[1]) shouldBe false
    }

    test("animated assets are delivered only to recipients that negotiated protocol one point three") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val animatedRecipient = testConnection(2L)
        val staticRecipient = testConnection(3L)
        val staticCapabilities = ProtocolCapabilities(ProtocolVersion(1, 2), EmotifyProtocolFeatures.supported)
        harness.openSupported(source, hello)
        harness.openSupported(animatedRecipient, hello)
        harness.openSupported(staticRecipient, ClientHello(staticCapabilities))
        harness.audiencePort.delegate = candidateAudiencePort(
            listOf(
                AudienceCandidateFixture(animatedRecipient),
                AudienceCandidateFixture(staticRecipient),
            ),
        )

        harness.engine.selectCustom(testPlayer(source), CustomEmotionSelection(animatedAsset.id, animatedAsset))
            .shouldBeInstanceOf<ServerSelectionResult.Published>()

        harness.transport.customAssets.map { recorded -> recorded.playerId } shouldBe listOf(animatedRecipient.playerId)
        harness.transport.customPlays.map { recorded -> recorded.playerId } shouldBe
            listOf(source.playerId, animatedRecipient.playerId)
    }

    test("a protocol one point two sender cannot upload an animated asset") {
        val harness = engineHarness(serverHello = serverHello, featureRegistry = EmotifyProtocolFeatures.registry)
        val source = testConnection(1L)
        val staticCapabilities = ProtocolCapabilities(ProtocolVersion(1, 2), EmotifyProtocolFeatures.supported)
        harness.openSupported(source, ClientHello(staticCapabilities))

        val result = harness.engine.selectCustom(
            testPlayer(source),
            CustomEmotionSelection(animatedAsset.id, animatedAsset),
        ).shouldBeInstanceOf<ServerSelectionResult.Rejected>()

        result.reason shouldBe SelectionRejectionReason.EMOTION_DISABLED
        harness.transport.customAssets.size shouldBe 0
        harness.transport.customPlays.size shouldBe 0
    }
})
