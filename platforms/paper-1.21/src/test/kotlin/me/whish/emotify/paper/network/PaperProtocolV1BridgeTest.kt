package me.whish.emotify.paper.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmojiTransfer
import me.whish.emotify.protocol.CustomEmotionPlay
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.wire.v1.ProtocolV1MaliciousCorpus
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1PayloadKind
import me.whish.emotify.wire.v1.WireDecodeException
import me.whish.emotify.wire.v1.WireEncodeException

@Suppress("unused")
class PaperProtocolV1BridgeTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)

    test("channel plan advertises every Protocol 1 id without expanding accepted directions") {
        PaperProtocolChannels.advertisedIncoming shouldContainExactly listOf(
            "emotify:server_hello",
            "emotify:client_hello",
            "emotify:select",
            "emotify:play",
            "emotify:selection_rejected",
            "emotify:custom_select",
            "emotify:custom_asset",
            "emotify:custom_asset_chunk",
            "emotify:custom_play",
        )
        PaperProtocolChannels.acceptedIncoming shouldContainExactly listOf(
            "emotify:client_hello",
            "emotify:select",
            "emotify:custom_select",
            "emotify:custom_asset_chunk",
        )
        PaperProtocolChannels.outgoing shouldContainExactly listOf(
            "emotify:server_hello",
            "emotify:play",
            "emotify:selection_rejected",
            "emotify:custom_asset",
            "emotify:custom_asset_chunk",
            "emotify:custom_play",
        )
        PaperProtocolChannels.outgoing
            .filterNot { channel -> channel == "emotify:custom_asset_chunk" }
            .forEach { channel ->
            PaperProtocolChannels.acceptsIncoming(channel) shouldBe false
        }
        PaperProtocolChannels.acceptsIncoming("emotify:custom_asset_chunk") shouldBe true
        PaperProtocolChannels.requiresBukkitSubscription("emotify:server_hello") shouldBe true
        PaperProtocolChannels.requiresBukkitSubscription("emotify:play") shouldBe true
        PaperProtocolChannels.requiresBukkitSubscription("emotify:selection_rejected") shouldBe true
        PaperProtocolChannels.requiresBukkitSubscription("emotify:custom_asset") shouldBe false
        PaperProtocolChannels.requiresBukkitSubscription("emotify:custom_asset_chunk") shouldBe false
        PaperProtocolChannels.requiresBukkitSubscription("emotify:custom_play") shouldBe false
    }

    test("incoming bodies use frozen raw bytes without a transport prefix") {
        PaperProtocolV1Bridge.decodeClientHello(hex("01 04 00")) shouldBe ClientHello(capabilities)
        PaperProtocolV1Bridge.decodeSelection(hex("03 61 3A 62")) shouldBe
            EmotionSelection(EmotionId.of("a:b"))
    }

    test("outgoing bodies use frozen raw bytes without a transport prefix") {
        PaperProtocolV1Bridge.encodeServerHello(
            ServerHello(capabilities, 250, EmotionCatalog.of(emptyList())),
        ).toList() shouldContainExactly hex("01 04 00 FA 01 00").toList()
        PaperProtocolV1Bridge.encodePlay(
            EmotionPlay(
                RuntimeEntityId.of(300),
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                EventSequence.of(300),
                EmotionId.of("a:b"),
            ),
        ).toList() shouldContainExactly hex(
            "AC 02 00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF AC 02 03 61 3A 62",
        ).toList()
        PaperProtocolV1Bridge.encodeSelectionRejected(
            SelectionRejected(SelectionRejectionCode(255), 0),
        ).toList() shouldContainExactly hex("FF 00").toList()
        val oversizedCatalog = EmotionCatalog.of(
            (0 until 100).map { index ->
                EmotionId.of("emotify:${index.toString().padStart(3, '0')}${"x".repeat(48)}")
            },
        )
        shouldThrow<WireEncodeException> {
            PaperProtocolV1Bridge.encodeServerHello(ServerHello(capabilities, 250, oversizedCatalog))
        }
    }

    test("custom payload bodies preserve content addressed data across the Paper bridge") {
        val asset = CustomEmojiAsset.create(
            CustomEmojiPixels.of(IntArray(64) { index -> index * 0x01010101 }),
        )
        val selection = CustomEmotionSelection(asset.id, asset)
        val play = CustomEmotionPlay(
            RuntimeEntityId.of(42),
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            EventSequence.of(7),
            asset.id,
        )

        PaperProtocolV1Bridge.decodeCustomSelection(
            ProtocolV1Codecs.customSelection.encodeToByteArray(selection),
        ) shouldBe selection
        ProtocolV1Codecs.customAsset.decode(
            PaperProtocolV1Bridge.encodeCustomAsset(CustomEmojiTransfer(asset)),
        ) shouldBe CustomEmojiTransfer(asset)
        ProtocolV1Codecs.customPlay.decode(PaperProtocolV1Bridge.encodeCustomPlay(play)) shouldBe play
    }

    test("incoming bridge preserves malicious corpus violations") {
        ProtocolV1MaliciousCorpus.inputs
            .filter { input ->
                input.payloadKind == ProtocolV1PayloadKind.CLIENT_HELLO ||
                    input.payloadKind == ProtocolV1PayloadKind.SELECTION
            }
            .forEach { input ->
                val exception = shouldThrow<WireDecodeException> {
                    if (input.payloadKind == ProtocolV1PayloadKind.CLIENT_HELLO) {
                        PaperProtocolV1Bridge.decodeClientHello(input.bytes)
                    } else {
                        PaperProtocolV1Bridge.decodeSelection(input.bytes)
                    }
                }
                exception.violation shouldBe input.violation
            }
    }
})

private fun hex(value: String): ByteArray = value
    .split(' ')
    .filter(String::isNotEmpty)
    .map { encoded -> encoded.toInt(16).toByte() }
    .toByteArray()
