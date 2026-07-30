package me.whish.emotify.wire.v1

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.protocol.ServerHelloEnvelope

@Suppress("unused")
class ProtocolV1MaliciousCorpusTest : FunSpec({
    ProtocolV1MaliciousCorpus.inputs.forEach { input ->
        test("pure codec rejects ${input.name}") {
            val exception = shouldThrow<WireDecodeException> {
                decode(input.payloadKind, input.bytes)
            }

            exception.violation shouldBe input.violation
        }
    }

    test("duplicate catalog remains a semantic handshake failure") {
        ProtocolV1Codecs.serverHello.decode(ProtocolV1MaliciousCorpus.duplicateCatalog) shouldBe
            ServerHelloEnvelope.DuplicateEmotionIds
    }

    test("invalid cooldown with duplicate catalog preserves the legacy semantic result") {
        ProtocolV1Codecs.serverHello.decode(ProtocolV1MaliciousCorpus.invalidCooldownWithDuplicateCatalog) shouldBe
            ServerHelloEnvelope.DuplicateEmotionIds
    }

    test("duplicate catalog cannot hide a truncated following entry") {
        shouldThrow<WireDecodeException> {
            ProtocolV1Codecs.serverHello.decode(ProtocolV1MaliciousCorpus.duplicateCatalogWithTruncatedTail)
        }.violation shouldBe WireDecodeViolation.TRUNCATED_BODY
    }
})

private fun decode(payloadKind: ProtocolV1PayloadKind, bytes: ByteArray): Any = when (payloadKind) {
    ProtocolV1PayloadKind.CLIENT_HELLO -> ProtocolV1Codecs.clientHello.decode(bytes)
    ProtocolV1PayloadKind.SERVER_HELLO -> ProtocolV1Codecs.serverHello.decode(bytes)
    ProtocolV1PayloadKind.SELECTION -> ProtocolV1Codecs.selection.decode(bytes)
    ProtocolV1PayloadKind.PLAY -> ProtocolV1Codecs.play.decode(bytes)
    ProtocolV1PayloadKind.SELECTION_REJECTED -> ProtocolV1Codecs.selectionRejected.decode(bytes)
}
