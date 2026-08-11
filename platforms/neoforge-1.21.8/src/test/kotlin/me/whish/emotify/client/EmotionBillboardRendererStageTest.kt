package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

@Suppress("unused")
class EmotionBillboardRendererStageTest : FunSpec({
    test("deferred world geometry flushes before the level render context ends") {
        val methods = EmotionBillboardRenderer::class.java.declaredMethods.mapTo(HashSet()) { method -> method.name }

        methods shouldContain "onAfterParticles"
        methods shouldContain "onAfterWeather"
        methods shouldNotContain "onAfterLevel"
    }
})
