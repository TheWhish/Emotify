package me.whish.emotify.wire.v1

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.domain.SelectionRejectionReason
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.protocol.ServerHelloEnvelope

@Suppress("unused")
class ProtocolV1GoldenTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)

    test("channel identifiers are frozen") {
        listOf(
            ProtocolV1Channels.CLIENT_HELLO,
            ProtocolV1Channels.SERVER_HELLO,
            ProtocolV1Channels.SELECT,
            ProtocolV1Channels.PLAY,
            ProtocolV1Channels.SELECTION_REJECTED,
            ProtocolV1Channels.CUSTOM_SELECT,
            ProtocolV1Channels.CUSTOM_ASSET,
            ProtocolV1Channels.CUSTOM_ASSET_CHUNK,
            ProtocolV1Channels.CUSTOM_PLAY,
        ) shouldContainExactly listOf(
            "emotify:client_hello",
            "emotify:server_hello",
            "emotify:select",
            "emotify:play",
            "emotify:selection_rejected",
            "emotify:custom_select",
            "emotify:custom_asset",
            "emotify:custom_asset_chunk",
            "emotify:custom_play",
        )
    }

    test("client hello has stable golden bytes") {
        ProtocolV1Codecs.clientHello.encodeToByteArray(ClientHello(capabilities)).toList() shouldContainExactly
            hex("01 04 00").toList()
    }

    test("minimum server hello has stable golden bytes") {
        val envelope = ServerHelloEnvelope.Valid(ServerHello(capabilities, 250, EmotionCatalog.of(emptyList())))

        ProtocolV1Codecs.serverHello.encodeToByteArray(envelope).toList() shouldContainExactly
            hex("01 04 00 FA 01 00").toList()
    }

    test("selection has stable golden bytes") {
        val selection = EmotionSelection(EmotionId.of("a:b"))

        ProtocolV1Codecs.selection.encodeToByteArray(selection).toList() shouldContainExactly
            hex("03 61 3A 62").toList()
    }

    test("play has stable golden bytes") {
        val play = EmotionPlay(
            RuntimeEntityId.of(300),
            UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            EventSequence.of(300),
            EmotionId.of("a:b"),
        )

        ProtocolV1Codecs.play.encodeToByteArray(play).toList() shouldContainExactly hex(
            "AC 02 00 11 22 33 44 55 66 77 88 99 AA BB CC DD EE FF AC 02 03 61 3A 62",
        ).toList()
    }

    test("every known rejection code has stable golden bytes") {
        SelectionRejectionReason.entries.forEachIndexed { code, reason ->
            val rejection = SelectionRejected(SelectionRejectionCode.from(reason), 1_200)

            ProtocolV1Codecs.selectionRejected.encodeToByteArray(rejection).toList() shouldContainExactly
                byteArrayOf(code.toByte(), 0xB0.toByte(), 0x09).toList()
        }
    }

    test("unknown rejection code has stable golden bytes") {
        val rejection = SelectionRejected(SelectionRejectionCode(255), 0)

        ProtocolV1Codecs.selectionRejected.encodeToByteArray(rejection).toList() shouldContainExactly
            hex("FF 00").toList()
    }
})

internal fun hex(value: String): ByteArray = value
    .split(' ')
    .filter(String::isNotEmpty)
    .map { encoded -> encoded.toInt(16).toByte() }
    .toByteArray()
