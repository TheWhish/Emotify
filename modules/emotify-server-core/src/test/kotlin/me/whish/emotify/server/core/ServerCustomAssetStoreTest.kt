package me.whish.emotify.server.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.SplittableRandom
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiFrame
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker
import me.whish.emotify.wire.v1.CustomEmojiAssetAssembler

@Suppress("unused")
class ServerCustomAssetStoreTest : FunSpec({
    fun denseAnimatedAsset(seed: Long): CustomEmojiAsset {
        val random = SplittableRandom(seed)
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

    fun assembly(asset: CustomEmojiAsset): me.whish.emotify.wire.v1.CustomEmojiAssetAssembly {
        val assembler = CustomEmojiAssetAssembler()
        return CustomEmojiAssetChunker.split(asset).mapNotNull { chunk ->
            assembler.acceptAssembly(chunk, 0L)
        }.single()
    }

    test("weighted LRU evicts retained asset and encoded chunk bytes together") {
        val store = ServerCustomAssetStore(maximumEntries = 16, maximumRetainedBytes = 1_100_000)
        val first = denseAnimatedAsset(1L)
        val second = denseAnimatedAsset(2L)

        store.put(first, assembly(first))
        store.put(second, assembly(second))

        store.snapshot().entries shouldBe 1
        store.find(first.id).shouldBeNull()
        store.find(second.id)?.asset shouldBe second
    }

    test("content addressed uploads are retained only once") {
        val store = ServerCustomAssetStore()
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it }))

        val first = store.put(asset, null)
        val before = store.snapshot()
        val second = store.put(asset, null)

        (first === second) shouldBe true
        store.snapshot() shouldBe before
    }

    test("session authorization cannot outlive a globally evicted asset") {
        val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val store = ServerCustomAssetStore(maximumEntries = 1)
        fun session(): ServerPlayerSession = ServerPlayerSession(
            capabilities,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
            EmotifyProtocolFeatures.registry,
            store,
        ).also { playerSession ->
            playerSession.receiveClientHello(ClientHello(capabilities))
        }
        val firstSession = session()
        val secondSession = session()
        val firstAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it }))
        val secondAsset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it + 1 }))

        firstSession.prepareCustomSelection(
            CustomEmotionSelection(firstAsset.id, firstAsset),
            TEST_ENABLED_POLICY,
            testPlayer(testConnection(1L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Ready>()
        secondSession.prepareCustomSelection(
            CustomEmotionSelection(secondAsset.id, secondAsset),
            TEST_ENABLED_POLICY,
            testPlayer(testConnection(2L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Ready>()

        val rejected = firstSession.prepareCustomSelection(
            CustomEmotionSelection(firstAsset.id, null),
            TEST_ENABLED_POLICY,
            testPlayer(testConnection(1L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Rejected>()
        rejected.reason shouldBe SelectionRejectionReason.CUSTOM_ASSET_MISSING
    }

    test("a session cannot select an asset uploaded by another session") {
        val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val store = ServerCustomAssetStore()
        fun session(): ServerPlayerSession = ServerPlayerSession(
            capabilities,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
            EmotifyProtocolFeatures.registry,
            store,
        ).also { playerSession -> playerSession.receiveClientHello(ClientHello(capabilities)) }
        val owner = session()
        val stranger = session()
        val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it }))

        owner.prepareCustomSelection(
            CustomEmotionSelection(asset.id, asset),
            TEST_ENABLED_POLICY,
            testPlayer(testConnection(1L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Ready>()
        val rejected = stranger.prepareCustomSelection(
            CustomEmotionSelection(asset.id, null),
            TEST_ENABLED_POLICY,
            testPlayer(testConnection(2L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Rejected>()

        rejected.reason shouldBe SelectionRejectionReason.CUSTOM_ASSET_MISSING
        store.snapshot().entries shouldBe 1
    }

    test("policy rejected inline and lossless assets never enter shared retention") {
        val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val store = ServerCustomAssetStore()
        val session = ServerPlayerSession(
            capabilities,
            1_200.milliseconds,
            FakeMonotonicTimeSource(),
            EmotifyProtocolFeatures.registry,
            store,
        ).also { playerSession -> playerSession.receiveClientHello(ClientHello(capabilities)) }
        val policy = TEST_ENABLED_POLICY.copy(
            maximumStaticCustomEmojiSize = 8,
            maximumAnimatedCustomEmojiSize = 8,
        )
        val legacy = CustomEmojiAsset.create(CustomEmojiPixels.of(16, IntArray(16 * 16) { it }))
        val lossless = CustomEmojiAsset.create(CustomEmojiPixels.of(128, IntArray(128 * 128) { it }))

        session.prepareCustomSelection(
            CustomEmotionSelection(legacy.id, legacy),
            policy,
            testPlayer(testConnection(1L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE
        val chunks = CustomEmojiAssetChunker.split(lossless)
        chunks.forEachIndexed { index, chunk ->
            session.receiveCustomAssetChunk(chunk, policy) shouldBe (index != chunks.lastIndex)
        }
        session.prepareCustomSelection(
            CustomEmotionSelection(lossless.id, null),
            policy,
            testPlayer(testConnection(1L)),
        ).shouldBeInstanceOf<CustomSelectionPreparation.Rejected>().reason shouldBe
            SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE
        store.snapshot() shouldBe ServerCustomAssetStoreSnapshot(0, 0L)
    }
})
