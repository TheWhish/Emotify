package me.whish.emotify.fabric.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class FabricClientConfigCodecTest : FunSpec({
    val first = EmotionId.of("emotify:smile")
    val second = EmotionId.of("emotify:heart")

    test("config round trip preserves reduced motion and ordered favorites") {
        val snapshot = FabricClientConfigSnapshot(true, listOf(first, second))

        FabricClientConfigCodec.decode(
            FabricClientConfigCodec.encode(snapshot),
            emptyList(),
        ) shouldBe snapshot
    }

    test("missing favorites retain manifest defaults") {
        val decoded = FabricClientConfigCodec.decode("reducedMotion=false\n", listOf(first, second))

        decoded.reducedMotion shouldBe false
        decoded.favorites shouldContainExactly listOf(first, second)
    }

    test("duplicate favorites are normalized without reordering") {
        val decoded = FabricClientConfigCodec.decode(
            "favorites=${first.value},${second.value},${first.value}\n",
            emptyList(),
        )

        decoded.favorites shouldContainExactly listOf(first, second)
    }

    test("malformed values and duplicate keys are rejected") {
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("reducedMotion=yes\n", emptyList())
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("favorites=invalid\n", emptyList())
        }
        shouldThrow<IllegalArgumentException> {
            FabricClientConfigCodec.decode("favorites=${first.value}\nfavorites=${second.value}\n", emptyList())
        }
    }
})
