package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import me.whish.emotify.client.input.PickerMovementAction
import me.whish.emotify.client.input.PickerMovementInputState

@Suppress("unused")
class EmotionPickerInteractionTest : FunSpec({
    test("label truncation fills available space without tiny word fragments") {
        val source = "Улыбка с потом"

        EmotionLabelTruncation.completePrefix(source, "Улыбка с п") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с по") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с пот") shouldBe "Улыбка с пот"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix(source, "Улыбка с ") shouldBe "Улыбка с"
        EmotionLabelTruncation.completePrefix("Воздушный поцелуй", "Воздушный") shouldBe "Воздушный"
        EmotionLabelTruncation.completePrefix("Смешок", "См") shouldBe "См"
    }

    test("picker toggle closes only on a fresh matching press") {
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = false,
            textInputFocused = false,
        ) shouldBe true
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = true,
            textInputFocused = false,
        ) shouldBe false
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = false,
            bindingDown = false,
            textInputFocused = false,
        ) shouldBe false
        EmotionPickerToggleGuard.shouldClose(
            matchesBinding = true,
            bindingDown = false,
            textInputFocused = true,
        ) shouldBe false
    }

    test("picker mouse binding has priority over widgets and movement bindings") {
        EmotionPickerMouseRouting.click(
            matchesPicker = true,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.CLOSE
    }

    test("movement mouse binding cannot activate widgets while movement is enabled") {
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.CONSUME_MOVEMENT
        EmotionPickerMouseRouting.consumeRelease(
            movementAllowed = true,
            matchesMovement = true,
        ) shouldBe true
    }

    test("movement mouse binding can focus and use search input") {
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = true,
            matchesMovement = true,
            enteringSearch = true,
        ) shouldBe EmotionPickerMouseDecision.DISPATCH
        EmotionPickerMouseRouting.click(
            matchesPicker = false,
            movementAllowed = false,
            matchesMovement = true,
            enteringSearch = false,
        ) shouldBe EmotionPickerMouseDecision.DISPATCH
        EmotionPickerMouseRouting.consumeRelease(
            movementAllowed = false,
            matchesMovement = true,
        ) shouldBe false
    }

    test("movement key state can be tracked without consuming search input") {
        val inputState = PickerMovementInputState()
        inputState.setScanCodeDown(PickerMovementAction.FORWARD, true)

        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = false,
            matchesMovement = inputState.isScanCodeDown(PickerMovementAction.FORWARD),
        ) shouldBe false
        inputState.isScanCodeDown(PickerMovementAction.FORWARD) shouldBe true
        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = true,
            matchesMovement = inputState.isScanCodeDown(PickerMovementAction.FORWARD),
        ) shouldBe true
        EmotionPickerKeyboardRouting.consumePress(
            movementAllowed = true,
            matchesMovement = false,
        ) shouldBe false
    }

    test("emotion drag starts only after a four pixel pointer movement") {
        EmotionPickerDragGesture.shouldStart(20.0, 30.0, 23.99, 30.0) shouldBe false
        EmotionPickerDragGesture.shouldStart(20.0, 30.0, 24.0, 30.0) shouldBe true
        EmotionPickerDragGesture.shouldStart(20.0, 30.0, 20.0, 26.0) shouldBe true
        EmotionPickerDragGesture.shouldStart(20.0, 30.0, 22.83, 32.83) shouldBe true
    }

    test("picker title is the language independent Emotify brand") {
        EmotionPickerBrand.TITLE shouldBe "Emotify"
    }

    test("drag preview follows a bounded inertial curve and settles exactly") {
        EmotionPickerDragPreview.MAXIMUM_LAG shouldBe 16.0
        val motion = EmotionPickerDragPreview.Motion(0.0, 0.0)
        var targetX = 0.0
        var targetY = 0.0
        repeat(12) {
            targetX += 2.0
            EmotionPickerDragPreview.advance(motion, targetX, targetY, 1.0 / 60.0)
            (motion.distanceTo(targetX, targetY) <= EmotionPickerDragPreview.MAXIMUM_LAG) shouldBe true
        }
        val xBeforeTurn = motion.x
        targetY += 2.0
        EmotionPickerDragPreview.advance(motion, targetX, targetY, 1.0 / 60.0)

        (motion.x > xBeforeTurn) shouldBe true
        (motion.x < targetX) shouldBe true
        (motion.y in 0.0..<targetY) shouldBe true
        repeat(180) {
            EmotionPickerDragPreview.advance(motion, targetX, targetY, 1.0 / 60.0)
        }
        abs(motion.x - targetX) shouldBe 0.0
        abs(motion.y - targetY) shouldBe 0.0
        motion.velocityX shouldBe 0.0
        motion.velocityY shouldBe 0.0
    }

    test("drag preview lifts smoothly and tilts with bounded horizontal inertia") {
        EmotionPickerDragPreview.liftScale(-1L) shouldBe 0.86
        (EmotionPickerDragPreview.liftScale(80_000_000L) > 1.0) shouldBe true
        EmotionPickerDragPreview.liftScale(160_000_000L) shouldBe 1.0
        EmotionPickerDragPreview.liftScale(1_000_000_000L) shouldBe 1.0
        EmotionPickerDragPreview.tiltDegrees(
            EmotionPickerDragPreview.Motion(0.0, 0.0, velocityX = 1_000.0),
        ) shouldBe 3.5
        EmotionPickerDragPreview.tiltDegrees(
            EmotionPickerDragPreview.Motion(0.0, 0.0, velocityX = -1_000.0),
        ) shouldBe -3.5
    }

    test("drag preview removes outward radial velocity at the lag boundary") {
        val motion = EmotionPickerDragPreview.Motion(0.0, 0.0, velocityX = -300.0, velocityY = 80.0)

        EmotionPickerDragPreview.advance(motion, 100.0, 0.0, 1.0 / 120.0)

        (abs(motion.distanceTo(100.0, 0.0) - EmotionPickerDragPreview.MAXIMUM_LAG) < 0.001) shouldBe true
        (motion.velocityX >= 0.0) shouldBe true
        (motion.velocityY > 0.0) shouldBe true
    }

    test("hover emphasis enters and leaves smoothly while recovering from a delayed frame") {
        val motion = EmotionPickerHoverAnimation.Motion()
        val entering = motion.advance(true, 1_000_000_000L)
        val delayed = motion.advance(true, 1_300_000_000L)
        val leaving = motion.advance(false, 1_316_666_667L)

        (entering in 0.0..<1.0) shouldBe true
        (delayed in entering..<1.0) shouldBe true
        (leaving in 0.0..<delayed) shouldBe true
        EmotionPickerHoverAnimation.nextEmphasis(0.0, true, 0.0) shouldBe 0.0
    }

    test("empty quick slot consumes clicks without activating while filled slot remains interactive") {
        EmotionPickerQuickSlotMouseRouting.click(assigned = false, hovered = true, button = 0) shouldBe
            EmotionPickerQuickSlotMouseDecision.CONSUME_EMPTY
        EmotionPickerQuickSlotMouseRouting.click(assigned = false, hovered = true, button = 1) shouldBe
            EmotionPickerQuickSlotMouseDecision.CONSUME_EMPTY
        EmotionPickerQuickSlotMouseRouting.click(assigned = true, hovered = true, button = 0) shouldBe
            EmotionPickerQuickSlotMouseDecision.ACTIVATE
        EmotionPickerQuickSlotMouseRouting.click(assigned = true, hovered = true, button = 1) shouldBe
            EmotionPickerQuickSlotMouseDecision.CLEAR
        EmotionPickerQuickSlotMouseRouting.click(assigned = true, hovered = false, button = 0) shouldBe
            EmotionPickerQuickSlotMouseDecision.DISPATCH
    }

    test("drop target emphasis approaches and releases without abrupt state changes") {
        val entering = EmotionPickerQuickSlotAnimation.nextTargetEmphasis(0.0, true, 1.0 / 60.0)
        val settled = generateSequence(entering) { current ->
            EmotionPickerQuickSlotAnimation.nextTargetEmphasis(current, true, 1.0 / 60.0)
        }.drop(30).first()
        val leaving = EmotionPickerQuickSlotAnimation.nextTargetEmphasis(settled, false, 1.0 / 60.0)

        (entering in 0.0..1.0) shouldBe true
        (entering > 0.0) shouldBe true
        (settled > 0.99) shouldBe true
        (leaving in 0.0..<settled) shouldBe true
    }

    test("successful drop uses a subpixel settle curve and finishes exactly at rest") {
        EmotionPickerQuickSlotAnimation.landingOffset(0L) shouldBe -3.0
        (abs(EmotionPickerQuickSlotAnimation.landingOffset(80_000_000L)) < 0.001) shouldBe true
        (abs(EmotionPickerQuickSlotAnimation.landingOffset(160_000_000L) - 0.75) < 0.001) shouldBe true
        EmotionPickerQuickSlotAnimation.landingOffset(320_000_000L) shouldBe 0.0
        (abs(EmotionPickerQuickSlotAnimation.landingScale(0L) - 0.82) < 0.001) shouldBe true
        (abs(EmotionPickerQuickSlotAnimation.landingScale(160_000_000L) - 1.045) < 0.001) shouldBe true
        EmotionPickerQuickSlotAnimation.landingScale(320_000_000L) shouldBe 1.0
        EmotionPickerQuickSlotAnimation.landingEmphasis(0L) shouldBe 1.0
        EmotionPickerQuickSlotAnimation.landingEmphasis(160_000_000L) shouldBe 0.25
        EmotionPickerQuickSlotAnimation.landingEmphasis(320_000_000L) shouldBe 0.0
        EmotionPickerQuickSlotAnimation.isLanding(319_999_999L) shouldBe true
        EmotionPickerQuickSlotAnimation.isLanding(320_000_000L) shouldBe false
    }
})
