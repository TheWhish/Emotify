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

        geometry.panelWidth shouldBe 246
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(20, 60, 59, 59, 20)
        geometry.tabBounds.map(EmotionPickerTabBounds::x) shouldContainExactly listOf(33, 57, 121, 184, 247)
        geometry.tabBounds.last().x + geometry.tabBounds.last().width shouldBe
            geometry.contentX + geometry.listWidth
        geometry.listX - geometry.panelX shouldBe EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
        geometry.panelX + geometry.panelWidth - (geometry.listX + geometry.listWidth) shouldBe
            EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
        geometry.panelY + geometry.panelHeight - (geometry.normalListY + geometry.normalListHeight) shouldBe
            EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
        geometry.normalListY - (geometry.tabY + EmotionPickerLayoutMetrics.TAB_HEIGHT) shouldBe
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
        geometry.normalListY - geometry.panelY shouldBe 41
        geometry.normalListY - geometry.tabY shouldBe 24
        geometry.searchListY - (geometry.searchFieldY + geometry.searchFieldHeight) shouldBe 4
        geometry.rowWidth shouldBe 222
        geometry.cellWidths shouldContainExactly listOf(68, 68, 67)
        geometry.gridWidth shouldBe 211
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
        EmotionPickerGridMetrics.gridWidth(geometry.listWidth, scrollbarVisible = true) shouldBe 211
        EmotionPickerGridMetrics.gridWidth(geometry.listWidth, scrollbarVisible = false) shouldBe 222
        EmotionPickerGridMetrics.cellWidths(
            geometry.listWidth,
            scrollbarVisible = true,
        ) shouldContainExactly listOf(68, 68, 67)
        EmotionPickerGridMetrics.cellWidths(
            geometry.listWidth,
            scrollbarVisible = false,
        ) shouldContainExactly listOf(72, 71, 71)
        geometry.panelX shouldBe 27
        geometry.panelX + geometry.panelWidth / 2 shouldBe 150
        geometry.sideActionX shouldBe 277
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 297
        geometry.sideActionY(0) shouldBe geometry.tabY
        geometry.sideActionY(1) shouldBe geometry.tabY + EmotionPickerSideActionLayout.STRIDE
        geometry.panelX shouldBe (300 - geometry.panelWidth) / 2
    }

    test("minimum panel preserves usable group widths and reserves search input space") {
        val geometry = EmotionPickerGeometry.calculate(200, 180, sections)

        geometry.panelWidth shouldBe 148
        geometry.panelX shouldBe 26
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(20, 27, 27, 26, 20)
        geometry.tabBounds.map(EmotionPickerTabBounds::x) shouldContainExactly listOf(32, 56, 87, 118, 148)
        geometry.searchListY - geometry.normalListY shouldBe 22
        geometry.normalListHeight - geometry.searchListHeight shouldBe 22
        geometry.rowWidth shouldBe 124
        geometry.cellWidths shouldContainExactly listOf(35, 35, 35)
        geometry.sideActionX shouldBe 178
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 198
        geometry.panelX shouldBe (200 - geometry.panelWidth) / 2
    }

    test("centered picker keeps its side action inside a narrow viewport") {
        val geometry = EmotionPickerGeometry.calculate(180, 180, sections)

        geometry.panelWidth shouldBe 128
        geometry.panelX shouldBe 26
        geometry.sideActionX shouldBe 158
        geometry.sideActionX + EmotionPickerSideActionLayout.SIZE shouldBe 178
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
