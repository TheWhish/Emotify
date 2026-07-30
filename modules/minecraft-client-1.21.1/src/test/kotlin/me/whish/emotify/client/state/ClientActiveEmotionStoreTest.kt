package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.protocol.EmotionPlay
import me.whish.emotify.protocol.EventSequence
import me.whish.emotify.protocol.RuntimeEntityId

@Suppress("unused")
class ClientActiveEmotionStoreTest : FunSpec({
    val happy = EmotionId.of("emotify:happy")
    val sad = EmotionId.of("emotify:sad")
    val sourceUuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    fun play(
        entityId: Int = 7,
        uuid: UUID = sourceUuid,
        sequence: Long = 1,
        emotionId: EmotionId = happy,
    ) = EmotionPlay(
        RuntimeEntityId.of(entityId),
        uuid,
        EventSequence.of(sequence),
        emotionId,
    )

    test("accepted play creates immutable state for the active connection") {
        val time = FakeMonotonicTimeSource(42)
        val store = ClientActiveEmotionStore(time)
        store.begin(3)

        store.activate(3, play()) shouldBe EmotionActivationResult.ADDED
        store.visibleFor(7, sourceUuid) shouldBe ActiveEmotion(
            RuntimeEntityId.of(7),
            sourceUuid,
            EventSequence.of(1),
            happy,
            EmotionAnimation.seedFor(
                sourceUuid.mostSignificantBits,
                sourceUuid.leastSignificantBits,
                1,
                happy,
            ),
            42,
        )
        store.size shouldBe 1
    }

    test("visual lifetime ends at its boundary while name tag grace remains active") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED

        time.advanceBy(EmotionAnimation.DURATION_MILLIS.milliseconds - 1.nanoseconds)
        store.visibleFor(7, sourceUuid)?.emotionId shouldBe happy
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
        time.advanceBy(1.nanoseconds)
        store.visibleFor(7, sourceUuid).shouldBeNull()
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
        store.size shouldBe 1
        store.removeExpired() shouldBe 0
        store.size shouldBe 1
    }

    test("name tag grace ends exactly four hundred milliseconds after the visual") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED

        time.advanceBy(
            EmotionAnimation.DURATION_MILLIS.milliseconds +
                ClientActiveEmotionStore.NAME_TAG_GRACE_MILLIS.milliseconds -
                1.nanoseconds,
        )
        store.visibleFor(7, sourceUuid).shouldBeNull()
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
        store.removeExpired() shouldBe 0
        time.advanceBy(1.nanoseconds)
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe false
        store.removeExpired() shouldBe 1
        store.size shouldBe 0
    }

    test("new emotion replaces grace-only state at the visual boundary") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        time.advanceBy(EmotionAnimation.DURATION_MILLIS.milliseconds)

        store.activate(1, play(sequence = 2, emotionId = sad)) shouldBe EmotionActivationResult.REPLACED

        store.visibleFor(7, sourceUuid)?.emotionId shouldBe sad
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
    }

    test("newer sequence replaces state and stale sequence preserves it") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play(sequence = 5)) shouldBe EmotionActivationResult.ADDED
        val originalSeed = store.visibleFor(7, sourceUuid)?.animationSeed
        time.advanceBy(100.milliseconds)

        store.activate(1, play(sequence = 5, emotionId = sad)) shouldBe EmotionActivationResult.STALE_SEQUENCE
        store.activate(1, play(sequence = 4, emotionId = sad)) shouldBe EmotionActivationResult.STALE_SEQUENCE
        store.visibleFor(7, sourceUuid)?.emotionId shouldBe happy
        store.visibleFor(7, sourceUuid)?.animationSeed shouldBe originalSeed
        store.activate(1, play(sequence = 6, emotionId = sad)) shouldBe EmotionActivationResult.REPLACED
        store.visibleFor(7, sourceUuid)?.emotionId shouldBe sad
        (store.visibleFor(7, sourceUuid)?.animationSeed != originalSeed) shouldBe true
        store.visibleFor(7, sourceUuid)?.startedAtNanos shouldBe 100.milliseconds.inWholeNanoseconds
    }

    test("identity mismatch and unknown emotion cannot become visible") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)

        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        store.visibleFor(7, UUID.randomUUID()).shouldBeNull()
        store.visibleFor(8, sourceUuid).shouldBeNull()
        store.activate(
            1,
            play(entityId = 8, sequence = 2, emotionId = EmotionId.of("other:unknown")),
        ) shouldBe EmotionActivationResult.UNKNOWN_EMOTION
        store.size shouldBe 1
    }

    test("same player moving to a new runtime entity id replaces the old index") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play(entityId = 7, sequence = 1)) shouldBe EmotionActivationResult.ADDED

        store.activate(1, play(entityId = 9, sequence = 2, emotionId = sad)) shouldBe
            EmotionActivationResult.REPLACED
        store.visibleFor(7, sourceUuid).shouldBeNull()
        store.visibleFor(9, sourceUuid)?.emotionId shouldBe sad
        store.size shouldBe 1
    }

    test("identity safe discard permanently removes an emotion hidden by player state") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED

        store.discard(7, UUID.randomUUID()) shouldBe false
        store.visibleFor(7, sourceUuid)?.emotionId shouldBe happy
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
        store.discard(7, sourceUuid) shouldBe true
        store.visibleFor(7, sourceUuid).shouldBeNull()
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe false
        store.size shouldBe 0
    }

    test("world clear removes name tag grace immediately") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        time.advanceBy(EmotionAnimation.DURATION_MILLIS.milliseconds)
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true

        store.clearWorld(1)

        store.shouldHideNameTagFor(7, sourceUuid) shouldBe false
        store.size shouldBe 0
    }

    test("render lookup does not read the clock and lifecycle filtering is bounded") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        time.rewindBy(1.nanoseconds)

        store.find(7, sourceUuid)?.emotionId shouldBe happy
        store.discardIf { active -> active.sourceUuid == sourceUuid } shouldBe 1
        store.find(7, sourceUuid).shouldBeNull()
    }

    test("capacity drops new state without evicting active entries") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time, maximumActive = 2)
        val firstUuid = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val secondUuid = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val thirdUuid = UUID.fromString("10000000-0000-0000-0000-000000000003")
        store.begin(1)
        store.activate(1, play(1, firstUuid, 1)) shouldBe EmotionActivationResult.ADDED
        store.activate(1, play(2, secondUuid, 2)) shouldBe EmotionActivationResult.ADDED

        store.activate(1, play(3, thirdUuid, 3)) shouldBe EmotionActivationResult.CAPACITY_REJECTED
        store.activate(1, play(1, firstUuid, 4, sad)) shouldBe EmotionActivationResult.REPLACED
        store.visibleFor(1, firstUuid)?.sourceUuid shouldBe firstUuid
        store.visibleFor(1, firstUuid)?.emotionId shouldBe sad
        store.visibleFor(2, secondUuid)?.sourceUuid shouldBe secondUuid
        store.visibleFor(3, thirdUuid).shouldBeNull()
        store.size shouldBe 2
    }

    test("name tag grace retains capacity until its exact boundary") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time, maximumActive = 1)
        val nextUuid = UUID.fromString("20000000-0000-0000-0000-000000000002")
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        time.advanceBy(EmotionAnimation.DURATION_MILLIS.milliseconds)

        store.activate(1, play(8, nextUuid, 2)) shouldBe EmotionActivationResult.CAPACITY_REJECTED
        store.shouldHideNameTagFor(7, sourceUuid) shouldBe true
        time.advanceBy(ClientActiveEmotionStore.NAME_TAG_GRACE_MILLIS.milliseconds)
        store.activate(1, play(8, nextUuid, 2)) shouldBe EmotionActivationResult.ADDED
        store.visibleFor(8, nextUuid)?.sourceUuid shouldBe nextUuid
        store.size shouldBe 1
    }

    test("connection and world lifecycle cannot clear or populate a newer session") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        store.begin(2)

        store.activate(1, play(sequence = 2)) shouldBe EmotionActivationResult.STALE_CONNECTION
        store.clearWorld(1)
        store.activate(2, play(sequence = 1)) shouldBe EmotionActivationResult.ADDED
        store.disconnect(1)
        store.size shouldBe 1
        store.clearWorld(2)
        store.size shouldBe 0
        store.activate(2, play(sequence = 2)) shouldBe EmotionActivationResult.ADDED
        store.disconnect(2)
        store.size shouldBe 0
    }

    test("monotonic time moving backwards fails fast") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)
        store.activate(1, play()) shouldBe EmotionActivationResult.ADDED
        time.rewindBy(1.nanoseconds)

        shouldThrow<IllegalStateException> {
            store.visibleFor(7, sourceUuid)
        }
    }

    test("default capacity remains bounded under forged unique sources") {
        val time = FakeMonotonicTimeSource()
        val store = ClientActiveEmotionStore(time)
        store.begin(1)

        repeat(1_000) { index ->
            val entityId = index + 1
            val uuid = UUID(0, entityId.toLong())
            store.activate(1, play(entityId, uuid, entityId.toLong()))
        }

        store.size shouldBe ClientActiveEmotionStore.MAXIMUM_ACTIVE
    }
})
