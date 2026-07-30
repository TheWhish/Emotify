package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId

@Suppress("unused")
class ClientPlayGateTest : FunSpec({
    val sourceId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val happy = EmotionId.of("emotify:happy")

    fun play(sequence: Long, emotionId: EmotionId = happy) = EmotionPlay(
        RuntimeEntityId.of(7),
        sourceId,
        EventSequence.of(sequence),
        emotionId,
    )

    test("gate accepts increasing valid plays once per active connection") {
        val gate = ClientPlayGate()
        gate.begin(4)

        gate.admit(4, BuiltInEmotionCatalog.catalog, play(1), 7, sourceId, true) shouldBe true
        gate.admit(4, BuiltInEmotionCatalog.catalog, play(1), 7, sourceId, true) shouldBe false
        gate.admit(4, BuiltInEmotionCatalog.catalog, play(2), 7, sourceId, true) shouldBe true
    }

    test("invalid plays never advance the accepted sequence") {
        val gate = ClientPlayGate()
        gate.begin(9)

        gate.admit(9, BuiltInEmotionCatalog.catalog, play(50), 8, sourceId, true) shouldBe false
        gate.admit(9, BuiltInEmotionCatalog.catalog, play(50), 7, UUID.randomUUID(), true) shouldBe false
        gate.admit(9, BuiltInEmotionCatalog.catalog, play(50), 7, sourceId, false) shouldBe false
        gate.admit(9, BuiltInEmotionCatalog.catalog, play(50, EmotionId.of("other:unknown")), 7, sourceId, true) shouldBe false
        gate.admit(9, BuiltInEmotionCatalog.catalog, play(1), 7, sourceId, true) shouldBe true
    }

    test("disconnect and reconnect reset sequence without accepting stale connection traffic") {
        val gate = ClientPlayGate()
        gate.begin(1)
        gate.admit(1, BuiltInEmotionCatalog.catalog, play(20), 7, sourceId, true) shouldBe true
        gate.disconnect(1)
        gate.begin(2)

        gate.admit(1, BuiltInEmotionCatalog.catalog, play(21), 7, sourceId, true) shouldBe false
        gate.admit(2, BuiltInEmotionCatalog.catalog, play(1), 7, sourceId, true) shouldBe true
    }

    test("a thousand forged entity identities do not allocate replay state or poison sequence") {
        val gate = ClientPlayGate()
        gate.begin(3)

        repeat(1_000) { forgedId ->
            gate.admit(3, BuiltInEmotionCatalog.catalog, play(1_000), forgedId + 8, sourceId, true) shouldBe false
        }

        gate.admit(3, BuiltInEmotionCatalog.catalog, play(1), 7, sourceId, true) shouldBe true
    }
})
