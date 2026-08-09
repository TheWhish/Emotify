package me.whish.emotify.client.interaction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

@Suppress("unused")
class EmotionBillboardHitTestTest : FunSpec({
    val ray = EmotionInteractionRay(
        origin = InteractionVector3(0.0, 1.6, 0.0),
        direction = InteractionVector3(0.0, 0.0, 1.0),
    )
    val area = EmotionBillboardHitArea(
        center = InteractionVector3(0.0, 1.6, 4.0),
        right = InteractionVector3(1.0, 0.0, 0.0),
        up = InteractionVector3(0.0, 1.0, 0.0),
        halfWidth = 0.25,
        halfHeight = 0.25,
    )

    test("direct crosshair hit returns its world distance") {
        EmotionBillboardHitDetector.intersectionDistance(ray, area, 4.5, 4.5) shouldBe
            (4.0 plusOrMinus 1.0e-12)
    }

    test("small interaction padding forgives near-edge clicks") {
        val nearEdge = area.copy(center = InteractionVector3(0.285, 1.6, 4.0))
        val outsidePadding = area.copy(center = InteractionVector3(0.295, 1.6, 4.0))

        EmotionBillboardHitDetector.intersectionDistance(ray, nearEdge, 4.5, 4.5) shouldBe
            (4.0 plusOrMinus 1.0e-12)
        EmotionBillboardHitDetector.intersectionDistance(ray, outsidePadding, 4.5, 4.5) shouldBe null
    }

    test("vanilla block reach is a hard maximum") {
        val distant = area.copy(center = InteractionVector3(0.0, 1.6, 4.5001))

        EmotionBillboardHitDetector.intersectionDistance(ray, distant, 4.5, 4.5) shouldBe null
    }

    test("solid block hit distance occludes the billboard") {
        EmotionBillboardHitDetector.intersectionDistance(ray, area, 4.5, 3.9) shouldBe null
        EmotionBillboardHitDetector.intersectionDistance(ray, area, 4.5, 4.0) shouldBe
            (4.0 plusOrMinus 1.0e-12)
    }

    test("billboards behind the camera and parallel rays cannot be hit") {
        val behind = area.copy(center = InteractionVector3(0.0, 1.6, -2.0))
        val parallelRay = ray.copy(direction = InteractionVector3(1.0, 0.0, 0.0))

        EmotionBillboardHitDetector.intersectionDistance(ray, behind, 4.5, 4.5) shouldBe null
        EmotionBillboardHitDetector.intersectionDistance(parallelRay, area, 4.5, 4.5) shouldBe null
    }

    test("camera-rotated billboard basis preserves exact bounds") {
        val rotatedArea = area.copy(
            right = InteractionVector3(0.0, 1.0, 0.0),
            up = InteractionVector3(-1.0, 0.0, 0.0),
        )

        EmotionBillboardHitDetector.intersectionDistance(ray, rotatedArea, 4.5, 4.5) shouldBe
            (4.0 plusOrMinus 1.0e-12)
    }

    test("custom emotion copy area covers upper sprites while clearing the player head") {
        val copyArea = CustomEmotionCopyHitArea.create(
            anchor = InteractionVector3(0.0, 1.6, 4.0),
            right = InteractionVector3(1.0, 0.0, 0.0),
            up = InteractionVector3(0.0, 1.0, 0.0),
        )
        val inside = ray.copy(origin = InteractionVector3(-0.45, 1.5001, 0.0))
        val outsideHorizontal = ray.copy(origin = InteractionVector3(-0.4501, 1.5001, 0.0))
        val outsideVertical = ray.copy(origin = InteractionVector3(-0.45, 1.4999, 0.0))

        copyArea.center.y + copyArea.halfHeight shouldBe (2.45 plusOrMinus 1.0e-12)
        copyArea.center.y - copyArea.halfHeight shouldBe (1.5 plusOrMinus 1.0e-12)
        copyArea.halfWidth * 2.0 shouldBe (CustomEmotionCopyHitArea.WIDTH_BLOCKS plusOrMinus 1.0e-12)
        copyArea.halfHeight * 2.0 shouldBe (CustomEmotionCopyHitArea.HEIGHT_BLOCKS plusOrMinus 1.0e-12)
        copyArea.padding shouldBe 0.0
        EmotionBillboardHitDetector.intersectionDistance(inside, copyArea, 4.5, 4.5) shouldBe
            (4.0 plusOrMinus 1.0e-12)
        EmotionBillboardHitDetector.intersectionDistance(outsideHorizontal, copyArea, 4.5, 4.5) shouldBe null
        EmotionBillboardHitDetector.intersectionDistance(outsideVertical, copyArea, 4.5, 4.5) shouldBe null
    }
})
