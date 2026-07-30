package me.whish.emotify.wire.v1

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.util.UUID
import kotlin.random.Random
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
import me.whish.emotify.protocol.ServerHelloEnvelope

@Suppress("unused")
class ProtocolV1PropertyTest : FunSpec({
    test("client hello round trips every generated capability shape deterministically") {
        checkAll(Arb.int(0..255), Arb.int(0..255), Arb.long()) { major, minor, features ->
            val value = ClientHello(ProtocolCapabilities(ProtocolVersion(major, minor), FeatureFlags(features)))

            verifyRoundTrip(ProtocolV1Codecs.clientHello, value)
        }
    }

    test("selection round trips generated valid identifiers deterministically") {
        checkAll(Arb.int(0..1_000_000)) { suffix ->
            val value = EmotionSelection(EmotionId.of("emotify:test_$suffix"))

            verifyRoundTrip(ProtocolV1Codecs.selection, value)
        }
    }

    test("play round trips generated identity and sequence values deterministically") {
        checkAll(Arb.int(1..Int.MAX_VALUE), Arb.long(1L..Long.MAX_VALUE), Arb.long(), Arb.long()) {
                entityId,
                sequence,
                mostSignificantBits,
                leastSignificantBits,
            ->
            val value = EmotionPlay(
                RuntimeEntityId.of(entityId),
                UUID(mostSignificantBits, leastSignificantBits),
                EventSequence.of(sequence),
                EmotionId.of("emotify:test"),
            )

            verifyRoundTrip(ProtocolV1Codecs.play, value)
        }
    }

    test("selection rejection round trips every generated wire code and retry") {
        checkAll(Arb.int(0..255), Arb.int(0..10_000)) { code, retryAfterMillis ->
            val value = SelectionRejected(SelectionRejectionCode(code), retryAfterMillis)

            verifyRoundTrip(ProtocolV1Codecs.selectionRejected, value)
        }
    }

    test("server hello round trips generated bounded catalogs deterministically") {
        checkAll(Arb.int(0..32), Arb.int(250..10_000), Arb.long()) { size, cooldownMillis, features ->
            val catalog = EmotionCatalog.of(List(size) { index -> EmotionId.of("emotify:test_$index") })
            val value = ServerHelloEnvelope.Valid(
                ServerHello(
                    ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(features)),
                    cooldownMillis,
                    catalog,
                ),
            )

            verifyRoundTrip(ProtocolV1Codecs.serverHello, value)
        }
    }

    test("every codec admits its exact maximum encoded body") {
        val maximumCatalog = EmotionCatalog.of(
            List(EmotionCatalog.MAX_SIZE) { index ->
                EmotionId.of("emotify:${"x".repeat(53)}${index.toString().padStart(3, '0')}")
            },
        )
        val values = listOf(
            ProtocolV1Codecs.clientHello.encodeToByteArray(
                ClientHello(ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(-1L))),
            ).size to ProtocolV1Limits.CLIENT_HELLO_BODY_BYTES,
            ProtocolV1Codecs.serverHello.encodeToByteArray(
                ServerHelloEnvelope.Valid(
                    ServerHello(
                        ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags(-1L)),
                        ServerHello.MAX_COOLDOWN_MILLIS,
                        maximumCatalog,
                    ),
                ),
            ).size to ProtocolV1Limits.SERVER_HELLO_BODY_BYTES,
            ProtocolV1Codecs.selection.encodeToByteArray(
                EmotionSelection(EmotionId.of("a:${"b".repeat(62)}")),
            ).size to ProtocolV1Limits.SELECT_BODY_BYTES,
            ProtocolV1Codecs.play.encodeToByteArray(
                EmotionPlay(
                    RuntimeEntityId.of(Int.MAX_VALUE),
                    UUID(0L, 0L),
                    EventSequence.of(Long.MAX_VALUE),
                    EmotionId.of("a:${"b".repeat(62)}"),
                ),
            ).size to ProtocolV1Limits.PLAY_BODY_BYTES,
            ProtocolV1Codecs.selectionRejected.encodeToByteArray(
                SelectionRejected(SelectionRejectionCode(255), 10_000),
            ).size to ProtocolV1Limits.SELECTION_REJECTED_BODY_BYTES,
        )

        values.forEach { (actual, expected) -> actual shouldBe expected }
    }

    test("encoder restores a destination checkpoint after a writer failure") {
        val writer = FailingWireWriter(failAtPosition = 3, initialPosition = 2)
        val value = EmotionSelection(EmotionId.of("emotify:test"))

        shouldThrow<IllegalStateException> {
            ProtocolV1Codecs.selection.encode(writer, value)
        }
        writer.position shouldBe 2
    }

    test("bounded random bodies only decode or fail with a typed wire violation") {
        val random = Random(0x454D4F54)
        val codecs: List<WireCodec<out Any>> = listOf(
            ProtocolV1Codecs.clientHello,
            ProtocolV1Codecs.serverHello,
            ProtocolV1Codecs.selection,
            ProtocolV1Codecs.play,
            ProtocolV1Codecs.selectionRejected,
        )

        codecs.forEach { codec ->
            repeat(300) {
                val bytes = random.nextBytes(random.nextInt(codec.maxBodyBytes + 2))
                val failure = runCatching { codec.decode(bytes) }.exceptionOrNull()
                if (failure != null) {
                    (failure is WireDecodeException) shouldBe true
                }
            }
        }
    }
})

private fun <T : Any> verifyRoundTrip(codec: WireCodec<T>, value: T) {
    val first = codec.encodeToByteArray(value)
    val second = codec.encodeToByteArray(value)

    first.size shouldBe codec.encodedSize(value)
    second.toList() shouldBe first.toList()
    codec.decode(first) shouldBe value
}

private class FailingWireWriter(
    private val failAtPosition: Int,
    initialPosition: Int,
) : WireWriter {
    private var currentPosition = initialPosition

    override val position: Int
        get() = currentPosition

    override fun writeUnsignedByte(value: Int) {
        if (currentPosition == failAtPosition) {
            throw IllegalStateException("Synthetic writer failure")
        }
        currentPosition++
    }

    override fun reset(position: Int) {
        currentPosition = position
    }
}
