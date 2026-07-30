package me.whish.emotify.paper.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.protocol.SelectionRejected
import me.whish.emotify.protocol.SelectionRejectionCode
import me.whish.emotify.protocol.ServerHello
import me.whish.emotify.wire.v1.ProtocolV1MaliciousCorpus
import me.whish.emotify.wire.v1.ProtocolV1PayloadKind
import me.whish.emotify.wire.v1.WireDecodeException

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
        )
        PaperProtocolChannels.acceptedIncoming shouldContainExactly listOf(
            "emotify:client_hello",
            "emotify:select",
        )
        PaperProtocolChannels.outgoing shouldContainExactly listOf(
            "emotify:server_hello",
            "emotify:play",
            "emotify:selection_rejected",
        )
        PaperProtocolChannels.outgoing.forEach { channel ->
            PaperProtocolChannels.acceptsIncoming(channel) shouldBe false
        }
    }

    test("incoming bodies use frozen raw bytes without a transport prefix") {
        PaperProtocolV1Bridge.decodeClientHello(hex("01 00 00")) shouldBe ClientHello(capabilities)
        PaperProtocolV1Bridge.decodeSelection(hex("03 61 3A 62")) shouldBe
            EmotionSelection(EmotionId.of("a:b"))
    }

    test("outgoing bodies use frozen raw bytes without a transport prefix") {
        PaperProtocolV1Bridge.encodeServerHello(
            ServerHello(capabilities, 250, EmotionCatalog.of(emptyList())),
        ).toList() shouldContainExactly hex("01 00 00 FA 01 00").toList()
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
