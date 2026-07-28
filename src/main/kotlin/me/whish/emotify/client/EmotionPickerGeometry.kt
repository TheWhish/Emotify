package me.whish.emotify.client

import kotlin.math.exp
import kotlin.math.roundToInt

internal data class EmotionPickerTabBounds(
    val x: Int,
    val width: Int,
)

internal object EmotionPickerVisualMetrics {
    const val GAP = 4
    const val FRAME_THICKNESS = 2
}

internal object EmotionPickerEdgeFade {
    const val HEIGHT = 12

    val color: Int
        get() = EmotionPickerTheme.edgeFade

    fun alphaAt(offset: Int): Int {
        require(offset in 0 until HEIGHT) { "Fade offset is outside the gradient: $offset" }
        val remaining = (HEIGHT - offset).toLong()
        val height = HEIGHT.toLong()
        val numerator = MAX_ALPHA * remaining * remaining * (3L * height - 2L * remaining)
        return (numerator / (height * height * height)).toInt()
    }

    fun targetVisibility(edgeDistance: Double): Double {
        val progress = (edgeDistance / REVEAL_DISTANCE).coerceIn(0.0, 1.0)
        return progress * progress * (3.0 - 2.0 * progress)
    }

    fun nextVisibility(current: Double, target: Double, elapsedSeconds: Double): Double {
        require(current in 0.0..1.0) { "Current fade visibility is outside the unit interval: $current" }
        require(target in 0.0..1.0) { "Target fade visibility is outside the unit interval: $target" }
        require(elapsedSeconds >= 0.0) { "Fade elapsed time must not be negative: $elapsedSeconds" }
        val response = 1.0 - exp(-VISIBILITY_RESPONSE * elapsedSeconds)
        return (current + (target - current) * response).coerceIn(0.0, 1.0)
    }

    fun alphaAt(offset: Int, visibility: Double): Int =
        (alphaAt(offset) * visibility.coerceIn(0.0, 1.0)).roundToInt()

    private const val MAX_ALPHA = 112L
    private const val REVEAL_DISTANCE = 10.0
    private const val VISIBILITY_RESPONSE = 8.0
}

internal object EmotionPickerHitArea {
    fun contains(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mouseX: Double,
        mouseY: Double,
    ): Boolean =
        width > 0 &&
            height > 0 &&
            mouseX >= x &&
            mouseX < x + width &&
            mouseY >= y &&
            mouseY < y + height
}

internal object EmotionPickerScrollMath {
    fun draggedAmount(
        current: Double,
        dragY: Double,
        maximum: Double,
        travel: Int,
    ): Double {
        require(maximum >= 0.0) { "Maximum scroll must not be negative: $maximum" }
        require(travel > 0) { "Scrollbar travel must be positive: $travel" }
        return (current + dragY * maximum / travel).coerceIn(0.0, maximum)
    }
}

internal object EmotionPickerListMetrics {
    const val SIDE_PADDING = EmotionPickerVisualMetrics.GAP + EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val CELL_GAP = EmotionPickerVisualMetrics.GAP
    const val EDGE_PADDING = SIDE_PADDING
    const val SCISSOR_INSET = EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val SCROLLBAR_WIDTH = 7
    const val SCROLLBAR_TRACK_WIDTH = 3
    const val SCROLLBAR_INNER_MARGIN = (SCROLLBAR_WIDTH - SCROLLBAR_TRACK_WIDTH) / 2
    const val SCROLLBAR_GAP = EmotionPickerVisualMetrics.GAP
    const val SCROLLBAR_RIGHT_PADDING =
        EmotionPickerVisualMetrics.GAP + EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val SCROLLBAR_RIGHT_INSET = SCROLLBAR_WIDTH + SCROLLBAR_RIGHT_PADDING
    const val VANILLA_ROW_BIAS = 2
    const val VANILLA_ROW_TOP_PADDING = 4
    const val ROW_HEADER_HEIGHT = EDGE_PADDING - VANILLA_ROW_TOP_PADDING

    fun fadeLeft(listX: Int): Int = listX + SCISSOR_INSET

    fun fadeRight(listRight: Int): Int =
        listRight - SCROLLBAR_RIGHT_INSET - SCROLLBAR_GAP
}

internal object EmotionPickerLayoutMetrics {
    const val PANEL_EDGE_PADDING = EmotionPickerVisualMetrics.GAP + EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val TITLE_AREA_TOP = EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val TITLE_AREA_HEIGHT = 15
    const val TITLE_TO_TABS_GAP = 0
    const val TAB_HEIGHT = 20
    const val CONTROL_GAP = 4
    const val SEARCH_FIELD_HEIGHT = 18
    const val CONTENT_BOTTOM_PADDING = PANEL_EDGE_PADDING
    const val TAB_Y_OFFSET = TITLE_AREA_TOP + TITLE_AREA_HEIGHT + TITLE_TO_TABS_GAP
    const val NORMAL_LIST_Y_OFFSET = TAB_Y_OFFSET + TAB_HEIGHT + CONTROL_GAP
    const val SEARCH_FIELD_Y_OFFSET = NORMAL_LIST_Y_OFFSET
    const val SEARCH_LIST_Y_OFFSET = SEARCH_FIELD_Y_OFFSET + SEARCH_FIELD_HEIGHT + CONTROL_GAP
}

internal data class EmotionPickerGeometry(
    val panelX: Int,
    val panelY: Int,
    val panelWidth: Int,
    val panelHeight: Int,
    val contentX: Int,
    val titleAreaY: Int,
    val titleAreaHeight: Int,
    val tabY: Int,
    val tabBounds: List<EmotionPickerTabBounds>,
    val listX: Int,
    val listWidth: Int,
    val normalListY: Int,
    val normalListHeight: Int,
    val searchListY: Int,
    val searchListHeight: Int,
    val searchFieldX: Int,
    val searchFieldY: Int,
    val searchFieldWidth: Int,
    val searchFieldHeight: Int,
    val rowWidth: Int,
    val cellWidths: List<Int>,
) {
    fun listY(searching: Boolean): Int = if (searching) searchListY else normalListY

    fun listHeight(searching: Boolean): Int = if (searching) searchListHeight else normalListHeight

    val gridX: Int
        get() = listX + (listWidth - rowWidth) / 2 - EmotionPickerListMetrics.VANILLA_ROW_BIAS

    val gridWidth: Int
        get() = cellWidths.sum() + (EmotionPickerGridLayout.COLUMNS - 1) * EmotionPickerListMetrics.CELL_GAP

    fun centeredTitleY(lineHeight: Int): Int = titleAreaY + (titleAreaHeight - lineHeight) / 2

    companion object {
        fun calculate(
            screenWidth: Int,
            screenHeight: Int,
            sections: List<EmotionPickerSection>,
        ): EmotionPickerGeometry {
            require(sections.isNotEmpty()) { "Emotion picker requires at least one section" }
            val panelWidth = (screenWidth - SCREEN_MARGIN * 2).coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
            val panelHeight = (screenHeight - SCREEN_MARGIN * 2).coerceIn(MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT)
            val panelX = (screenWidth - panelWidth) / 2
            val panelY = (screenHeight - panelHeight) / 2
            val contentX = panelX + EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
            val contentWidth = panelWidth - EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING * 2
            val tabBounds = calculateTabs(contentX, contentWidth, sections)
            val normalListY = panelY + EmotionPickerLayoutMetrics.NORMAL_LIST_Y_OFFSET
            val searchListY = panelY + EmotionPickerLayoutMetrics.SEARCH_LIST_Y_OFFSET
            val listBottom = panelY + panelHeight - EmotionPickerLayoutMetrics.CONTENT_BOTTOM_PADDING
            val normalListHeight = (listBottom - normalListY).coerceAtLeast(EmotionPickerGridLayout.ROW_STRIDE)
            val searchListHeight = (listBottom - searchListY).coerceAtLeast(EmotionPickerGridLayout.ROW_STRIDE)
            val rowWidth = (contentWidth - EmotionPickerListMetrics.SIDE_PADDING * 2)
                .coerceAtLeast(EmotionPickerGridLayout.COLUMNS)
            val gridWidth = (
                contentWidth -
                    EmotionPickerListMetrics.SIDE_PADDING -
                    EmotionPickerListMetrics.SCROLLBAR_GAP -
                    EmotionPickerListMetrics.SCROLLBAR_WIDTH -
                    EmotionPickerListMetrics.SCROLLBAR_RIGHT_PADDING
                ).coerceAtLeast(EmotionPickerGridLayout.COLUMNS)
            val cellWidths = distributeWidth(
                gridWidth - (EmotionPickerGridLayout.COLUMNS - 1) * EmotionPickerListMetrics.CELL_GAP,
                EmotionPickerGridLayout.COLUMNS,
            )
            return EmotionPickerGeometry(
                panelX,
                panelY,
                panelWidth,
                panelHeight,
                contentX,
                panelY + EmotionPickerLayoutMetrics.TITLE_AREA_TOP,
                EmotionPickerLayoutMetrics.TITLE_AREA_HEIGHT,
                panelY + EmotionPickerLayoutMetrics.TAB_Y_OFFSET,
                tabBounds,
                contentX,
                contentWidth,
                normalListY,
                normalListHeight,
                searchListY,
                searchListHeight,
                contentX,
                panelY + EmotionPickerLayoutMetrics.SEARCH_FIELD_Y_OFFSET,
                contentWidth,
                EmotionPickerLayoutMetrics.SEARCH_FIELD_HEIGHT,
                rowWidth,
                cellWidths,
            )
        }

        private fun calculateTabs(
            contentX: Int,
            contentWidth: Int,
            sections: List<EmotionPickerSection>,
        ): List<EmotionPickerTabBounds> {
            val groupCount = sections.count { section -> section.kind == EmotionPickerSectionKind.GROUP }
            val iconCount = sections.size - groupCount
            val gapsWidth = (sections.size - 1) * TAB_GAP
            val groupSpace = contentWidth - iconCount * ICON_TAB_WIDTH - gapsWidth
            val groupWidths = if (groupCount == 0) emptyList() else distributeWidth(groupSpace, groupCount)
            var groupIndex = 0
            var tabX = contentX
            return java.util.List.copyOf(
                sections.map { section ->
                    val tabWidth = if (section.kind == EmotionPickerSectionKind.GROUP) {
                        groupWidths[groupIndex++]
                    } else {
                        ICON_TAB_WIDTH
                    }
                    EmotionPickerTabBounds(tabX, tabWidth).also {
                        tabX += tabWidth + TAB_GAP
                    }
                },
            )
        }

        private fun distributeWidth(availableWidth: Int, itemCount: Int): List<Int> {
            require(itemCount > 0) { "Item count must be positive" }
            val baseWidth = (availableWidth / itemCount).coerceAtLeast(1)
            val remainder = (availableWidth - baseWidth * itemCount).coerceAtLeast(0)
            return List(itemCount) { index -> baseWidth + if (index < remainder) 1 else 0 }
        }

        private const val SCREEN_MARGIN = 10
        private const val MIN_PANEL_WIDTH = 180
        private const val MAX_PANEL_WIDTH = 246
        private const val MIN_PANEL_HEIGHT = 150
        private const val MAX_PANEL_HEIGHT = 226
        private const val ICON_TAB_WIDTH = 20
        private const val TAB_GAP = EmotionPickerVisualMetrics.GAP
    }
}
