package me.whish.emotify.client.picker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog

@Suppress("unused")
class EmotionPickerGeometryTest : FunSpec({
    val sections = EmotionPickerModel.from(BuiltInEmotionCatalog.catalog).sections

    test("maximum panel pins square virtual tabs around equal group tabs") {
        val geometry = EmotionPickerGeometry.calculate(300, 300, sections)

        geometry.panelWidth shouldBe 250
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(22, 57, 57, 56, 22)
        geometry.tabBounds.map(EmotionPickerTabBounds::x) shouldContainExactly listOf(35, 61, 122, 183, 243)
        geometry.tabBounds.last().x + geometry.tabBounds.last().width shouldBe
            geometry.contentX + geometry.listWidth
        geometry.listX - geometry.panelX shouldBe 10
        geometry.panelX + geometry.panelWidth - (geometry.listX + geometry.listWidth) shouldBe
            10
        geometry.panelY + geometry.panelHeight - (geometry.normalListY + geometry.normalListHeight) shouldBe
            geometry.listX - geometry.panelX
        geometry.quickSlotY - (geometry.tabY + EmotionPickerLayoutMetrics.TAB_HEIGHT) shouldBe
            EmotionPickerVisualMetrics.GAP
        geometry.normalListY - (geometry.quickSlotY + geometry.quickSlotHeight) shouldBe
            EmotionPickerVisualMetrics.GAP
        geometry.tabBounds.zipWithNext().forEach { (left, right) ->
            right.x - (left.x + left.width) shouldBe EmotionPickerVisualMetrics.GAP
        }
        geometry.titleAreaY - geometry.panelY shouldBe EmotionPickerLayoutMetrics.TITLE_AREA_TOP
        geometry.titleAreaHeight shouldBe EmotionPickerLayoutMetrics.TITLE_AREA_HEIGHT
        val titleY = geometry.centeredTitleY(9)
        titleY - (geometry.panelY + EmotionPickerVisualMetrics.FRAME_THICKNESS) shouldBe
            geometry.tabY - (titleY + 9)
        geometry.tabY - geometry.panelY shouldBe 17
        EmotionPickerLayoutMetrics.TAB_HEIGHT shouldBe 22
        geometry.quickSlotY - geometry.panelY shouldBe 43
        geometry.quickSlotHeight shouldBe 22
        geometry.normalListY - geometry.panelY shouldBe 69
        geometry.normalListY - geometry.tabY shouldBe 52
        geometry.quickSlotBounds.map(EmotionPickerQuickSlotBounds::x) shouldContainExactly
            listOf(35, 61, 87, 113, 139, 165, 191, 217, 243)
        geometry.quickSlotBounds.map(EmotionPickerQuickSlotBounds::width) shouldContainExactly
            List(9) { 22 }
        geometry.quickSlotBounds.zipWithNext { left, right -> right.x - left.right } shouldContainExactly
            List(8) { EmotionPickerVisualMetrics.GAP }
        geometry.quickSlotBounds.first().x shouldBe geometry.contentX
        geometry.quickSlotBounds.last().right shouldBe geometry.contentX + geometry.listWidth
        geometry.quickSlotBounds.all { bounds ->
            bounds.y == geometry.quickSlotY && bounds.height == bounds.width
        } shouldBe true
        geometry.quickSlotBounds.first().previewSize shouldBe 14
        geometry.tabBounds.first() shouldBe EmotionPickerTabBounds(
            geometry.quickSlotBounds.first().x,
            geometry.quickSlotBounds.first().width,
        )
        geometry.tabBounds.last() shouldBe EmotionPickerTabBounds(
            geometry.quickSlotBounds.last().x,
            geometry.quickSlotBounds.last().width,
        )
        geometry.tabBounds[1].x shouldBe geometry.quickSlotBounds[1].x
        EmotionPickerLayoutMetrics.QUICK_SLOT_MAXIMUM_SIZE shouldBe 22
        EmotionPickerLayoutMetrics.QUICK_SLOT_ICON_SIZE shouldBe 14
        (EmotionPickerLayoutMetrics.QUICK_SLOT_MAXIMUM_SIZE - EmotionPickerLayoutMetrics.QUICK_SLOT_ICON_SIZE) / 2 shouldBe
            EmotionPickerVisualMetrics.GAP
        abs(
            (geometry.quickSlotBounds.first().x - geometry.panelX) -
                (geometry.panelX + geometry.panelWidth - geometry.quickSlotBounds.last().right),
        ) shouldBeLessThanOrEqual 1
        geometry.quickSlotAt(35.0, geometry.quickSlotY.toDouble()) shouldBe 0
        geometry.quickSlotAt(56.999, (geometry.quickSlotY + 21).toDouble()) shouldBe 0
        geometry.quickSlotAt(60.999, (geometry.quickSlotY + 21).toDouble()) shouldBe -1
        geometry.quickSlotAt(61.0, geometry.quickSlotY.toDouble()) shouldBe 1
        geometry.quickSlotAt(60.0, (geometry.quickSlotY + 22).toDouble()) shouldBe -1
        geometry.searchListY - (geometry.searchFieldY + geometry.searchFieldHeight) shouldBe 4
        geometry.rowWidth shouldBe 218
        geometry.cellWidths shouldContainExactly listOf(67, 66, 66)
        geometry.gridWidth shouldBe 207
        geometry.gridX shouldBe geometry.listX + EmotionPickerListMetrics.SIDE_PADDING
        geometry.gridX + geometry.gridWidth shouldBe
            geometry.listX + geometry.listWidth -
            EmotionPickerListMetrics.SCROLLBAR_GAP -
            EmotionPickerListMetrics.SCROLLBAR_WIDTH -
            EmotionPickerListMetrics.SCROLLBAR_RIGHT_PADDING
        geometry.searchFieldX shouldBe geometry.contentX
        geometry.searchFieldWidth shouldBe geometry.listWidth
        EmotionPickerListMetrics.SIDE_PADDING - EmotionPickerVisualMetrics.FRAME_THICKNESS shouldBe
            EmotionPickerVisualMetrics.GAP
        EmotionPickerListMetrics.CELL_GAP shouldBe EmotionPickerVisualMetrics.GAP
        EmotionPickerListMetrics.SCROLLBAR_GAP shouldBe EmotionPickerVisualMetrics.GAP
        EmotionPickerListMetrics.SCROLLBAR_RIGHT_PADDING - EmotionPickerVisualMetrics.FRAME_THICKNESS shouldBe
            EmotionPickerVisualMetrics.GAP
        EmotionPickerListMetrics.EDGE_PADDING - EmotionPickerVisualMetrics.FRAME_THICKNESS shouldBe
            EmotionPickerVisualMetrics.GAP
        EmotionPickerListMetrics.SCISSOR_INSET shouldBe EmotionPickerVisualMetrics.FRAME_THICKNESS
        val gridWidth = geometry.cellWidths.sum() +
            (EmotionPickerGridLayout.COLUMNS - 1) * EmotionPickerListMetrics.CELL_GAP
        geometry.listWidth - EmotionPickerListMetrics.SIDE_PADDING - gridWidth shouldBe
            EmotionPickerListMetrics.SCROLLBAR_GAP +
            EmotionPickerListMetrics.SCROLLBAR_WIDTH +
            EmotionPickerListMetrics.SCROLLBAR_RIGHT_PADDING
        EmotionPickerGridMetrics.gridWidth(geometry.listWidth, scrollbarVisible = true) shouldBe 207
        EmotionPickerGridMetrics.gridWidth(geometry.listWidth, scrollbarVisible = false) shouldBe 218
        EmotionPickerGridMetrics.cellWidths(
            geometry.listWidth,
            scrollbarVisible = true,
        ) shouldContainExactly listOf(67, 66, 66)
        EmotionPickerGridMetrics.cellWidths(
            geometry.listWidth,
            scrollbarVisible = false,
        ) shouldContainExactly listOf(70, 70, 70)
        geometry.panelX shouldBe 25
        geometry.panelX + geometry.panelWidth / 2 shouldBe 150
        geometry.sideActionX shouldBe 279
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 299
        geometry.sideActionY(0) shouldBe geometry.tabY
        geometry.sideActionY(1) shouldBe geometry.tabY + EmotionPickerSideActionLayout.STRIDE
        geometry.panelX shouldBe (300 - geometry.panelWidth) / 2
    }

    test("minimum panel preserves usable group widths and reserves search input space") {
        val geometry = EmotionPickerGeometry.calculate(200, 180, sections)

        geometry.panelWidth shouldBe 150
        geometry.panelX shouldBe 25
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(22, 24, 24, 23, 22)
        geometry.tabBounds.map(EmotionPickerTabBounds::x) shouldContainExactly listOf(34, 60, 88, 116, 143)
        geometry.searchListY - geometry.normalListY shouldBe 22
        geometry.normalListHeight - geometry.searchListHeight shouldBe 22
        geometry.rowWidth shouldBe 119
        geometry.cellWidths shouldContainExactly listOf(34, 33, 33)
        geometry.sideActionX shouldBe 179
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 199
        geometry.panelX shouldBe (200 - geometry.panelWidth) / 2
        geometry.quickSlotBounds.map(EmotionPickerQuickSlotBounds::width) shouldContainExactly
            List(9) { 11 }
        geometry.quickSlotBounds.all { bounds -> bounds.width == bounds.height } shouldBe true
        geometry.quickSlotBounds.first().previewSize shouldBe 7
        geometry.quickSlotBounds.first().x shouldBe geometry.contentX
        geometry.quickSlotBounds.last().right shouldBe geometry.contentX + geometry.listWidth
        geometry.quickSlotBounds.zipWithNext { left, right -> right.x - left.right }.distinct() shouldContainExactly
            listOf(EmotionPickerVisualMetrics.GAP)
    }

    test("centered picker keeps its side action inside a narrow viewport") {
        val geometry = EmotionPickerGeometry.calculate(180, 180, sections)

        geometry.panelWidth shouldBe 130
        geometry.panelX shouldBe 25
        geometry.sideActionX shouldBe 159
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 179
    }

    test("responsive content preserves one integer quick-slot lattice") {
        (180..420).forEach { screenWidth ->
            val geometry = EmotionPickerGeometry.calculate(screenWidth, 180, sections)
            val slotSize = geometry.quickSlotBounds.first().width

            geometry.listWidth shouldBe
                EmotionPickerLayoutMetrics.QUICK_SLOT_COUNT * slotSize +
                (EmotionPickerLayoutMetrics.QUICK_SLOT_COUNT - 1) * EmotionPickerLayoutMetrics.QUICK_SLOT_GAP
            geometry.quickSlotBounds.zipWithNext { left, right -> right.x - left.right }.distinct() shouldContainExactly
                listOf(EmotionPickerLayoutMetrics.QUICK_SLOT_GAP)
            geometry.quickSlotBounds.first().x shouldBe geometry.contentX
            geometry.quickSlotBounds.last().right shouldBe geometry.contentX + geometry.listWidth
            geometry.panelY + geometry.panelHeight - (geometry.normalListY + geometry.normalListHeight) shouldBe
                geometry.contentX - geometry.panelX
        }
    }

    test("edge fade softly blends the complete list viewport") {
        EmotionPickerEdgeFade.HEIGHT shouldBe 12
        val alphas = List(EmotionPickerEdgeFade.HEIGHT, EmotionPickerEdgeFade::alphaAt)
        alphas.first() shouldBe 112
        alphas.last() shouldBe 2
        alphas.zipWithNext().forEach { (current, next) ->
            (current - next) shouldBeLessThanOrEqual 14
        }
        alphas shouldContainExactly alphas.sortedDescending()

        EmotionPickerEdgeFade.targetVisibility(0.0) shouldBe 0.0
        EmotionPickerEdgeFade.targetVisibility(5.0) shouldBe 0.5
        EmotionPickerEdgeFade.targetVisibility(10.0) shouldBe 1.0
        val appearing = EmotionPickerEdgeFade.nextVisibility(0.0, 1.0, 1.0 / 60.0)
        val disappearing = EmotionPickerEdgeFade.nextVisibility(1.0, 0.0, 1.0 / 60.0)
        (appearing in 0.0..0.2) shouldBe true
        (disappearing in 0.8..<1.0) shouldBe true
        EmotionPickerEdgeFade.alphaAt(0, appearing) shouldBe 14
        EmotionPickerEdgeFade.alphaAt(0, 0.0) shouldBe 0
        EmotionPickerEdgeFade.alphaAt(0, 1.0) shouldBe 112
        EmotionPickerListMetrics.fadeLeft(100) shouldBe 102
        EmotionPickerListMetrics.fadeRight(334) shouldBe 332
    }

    test("visual search field uses one half-open hit area") {
        EmotionPickerHitArea.contains(10, 20, 80, 18, 10.0, 20.0) shouldBe true
        EmotionPickerHitArea.contains(10, 20, 80, 18, 89.999, 37.999) shouldBe true
        EmotionPickerHitArea.contains(10, 20, 80, 18, 90.0, 30.0) shouldBe false
        EmotionPickerHitArea.contains(10, 20, 80, 18, 40.0, 38.0) shouldBe false
    }

    test("scrollbar drag stays attached to the pointer and clamps immediately") {
        EmotionPickerScrollMath.draggedAmount(40.0, 5.0, 200.0, 100) shouldBe 50.0
        EmotionPickerScrollMath.draggedAmount(195.0, 5.0, 200.0, 100) shouldBe 200.0
        EmotionPickerScrollMath.draggedAmount(5.0, -5.0, 200.0, 100) shouldBe 0.0
        EmotionPickerScrollMath.draggedAmount(0.0, 20.0, 0.0, 100) shouldBe 0.0
    }

    test("critical scroll motion starts gently remains monotonic and settles exactly") {
        val motion = EmotionPickerScrollMath.Motion()
        val positions = ArrayList<Double>()
        repeat(120) {
            EmotionPickerScrollMath.advance(motion.position, 32.0, motion.velocity, 1.0 / 60.0, motion)
            positions += motion.position
        }

        (positions.first() in 0.0..2.0) shouldBe true
        positions.zipWithNext().all { (current, next) -> next >= current && next <= 32.0 } shouldBe true
        motion.position shouldBe 32.0
        motion.velocity shouldBe 0.0
    }

    test("critical scroll motion is frame-rate independent and reverses without a wrong-way jerk") {
        fun simulate(frames: Int, elapsedSeconds: Double): EmotionPickerScrollMath.Motion {
            val motion = EmotionPickerScrollMath.Motion()
            repeat(frames) {
                EmotionPickerScrollMath.advance(motion.position, 96.0, motion.velocity, elapsedSeconds, motion)
            }
            return motion
        }

        val sixtyFps = simulate(30, 1.0 / 60.0)
        val thirtyFps = simulate(15, 1.0 / 30.0)
        (abs(sixtyFps.position - thirtyFps.position) < 0.01) shouldBe true

        val reversed = EmotionPickerScrollMath.Motion()
        EmotionPickerScrollMath.advance(40.0, 0.0, 300.0, 1.0 / 60.0, reversed)
        (reversed.position in 38.0..<40.0) shouldBe true
        (reversed.velocity < 0.0) shouldBe true

        val extremeReversal = EmotionPickerScrollMath.Motion()
        EmotionPickerScrollMath.advance(1.0, 0.0, 720.0, 1.0 / 60.0, extremeReversal)
        extremeReversal.position shouldBe 1.0
        extremeReversal.velocity shouldBe 0.0
    }
})
