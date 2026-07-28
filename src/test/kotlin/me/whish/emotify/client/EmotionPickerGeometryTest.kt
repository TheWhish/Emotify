package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionCatalog

class EmotionPickerGeometryTest : FunSpec({
    val sections = EmotionPickerModel.from(EmotionCatalog.BUILT_IN).sections

    test("maximum panel pins square virtual tabs around equal group tabs") {
        val geometry = EmotionPickerGeometry.calculate(300, 300, sections)

        geometry.panelWidth shouldBe 246
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(20, 91, 91, 20)
        geometry.tabBounds.map(EmotionPickerTabBounds::x) shouldContainExactly listOf(33, 57, 152, 247)
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
    }

    test("minimum panel preserves usable group widths and reserves search input space") {
        val geometry = EmotionPickerGeometry.calculate(200, 180, sections)

        geometry.panelWidth shouldBe 180
        geometry.tabBounds.map(EmotionPickerTabBounds::width) shouldContainExactly listOf(20, 58, 58, 20)
        geometry.searchListY - geometry.normalListY shouldBe 22
        geometry.normalListHeight - geometry.searchListHeight shouldBe 22
        geometry.rowWidth shouldBe 156
        geometry.cellWidths shouldContainExactly listOf(46, 46, 45)
    }

    test("edge fade softly blends the complete list viewport") {
        EmotionPickerEdgeFade.HEIGHT shouldBe 12
        EmotionPickerEdgeFade.color shouldBe EmotionPickerTheme.outline

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

    test("smooth wheel scrolling reaches both boundaries without an asymptotic tail") {
        val firstFrame = EmotionPickerScrollMath.animatedAmount(0.0, 32.0, 1.0 / 60.0)

        (firstFrame in 0.0..<32.0) shouldBe true
        EmotionPickerScrollMath.animatedAmount(198.0, 200.0, 1.0 / 60.0) shouldBe 200.0
        EmotionPickerScrollMath.animatedAmount(2.0, 0.0, 1.0 / 60.0) shouldBe 0.0
        EmotionPickerScrollMath.animatedAmount(40.0, 40.0, 1.0 / 60.0) shouldBe 40.0
    }
})
