package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

@Suppress("unused")
class PaperWorldIdentityTest : FunSpec({
    test("dimension ordinals are stable distinct and reset without retaining world objects") {
        val registry = PaperDimensionOrdinalRegistry()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        registry.resolve(first) shouldBe 1
        registry.resolve(first) shouldBe 1
        registry.resolve(second) shouldBe 2
        registry.clear()
        registry.resolve(second) shouldBe 1
    }

    test("region keys use floor based chunk coordinates including negative positions") {
        PaperRegionKey.fromPosition(0.0, 15.999) shouldBe packedChunk(0, 0)
        PaperRegionKey.fromPosition(16.0, 32.0) shouldBe packedChunk(1, 2)
        PaperRegionKey.fromPosition(-0.001, -16.001) shouldBe packedChunk(-1, -2)
    }
})

private fun packedChunk(x: Int, z: Int): Long =
    (x.toLong() and 0xFFFF_FFFFL) or ((z.toLong() and 0xFFFF_FFFFL) shl 32)
