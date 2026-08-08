package me.whish.emotify.client.picker

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class EmotionPickerTabBounds(
    val x: Int,
    val width: Int,
)

object EmotionPickerVisualMetrics {
    const val GAP = 4
    const val FRAME_THICKNESS = 2
}

object EmotionPickerEdgeFade {
    const val HEIGHT = 12

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

object EmotionPickerHitArea {
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

object EmotionPickerScrollMath {
    class Motion(
        var position: Double = 0.0,
        var velocity: Double = 0.0,
    )

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

    fun advance(
        current: Double,
        target: Double,
        velocity: Double,
        elapsedSeconds: Double,
        output: Motion,
    ) {
        require(current.isFinite() && target.isFinite() && velocity.isFinite()) {
            "Scroll motion must contain only finite values"
        }
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Scroll elapsed time must be finite and non-negative: $elapsedSeconds"
        }
        val distance = target - current
        if (abs(distance) <= SNAP_DISTANCE && abs(velocity) <= SNAP_VELOCITY) {
            output.position = target
            output.velocity = 0.0
            return
        }
        val directedVelocity = if (distance * velocity < 0.0) {
            velocity * REVERSAL_VELOCITY_RETENTION
        } else {
            velocity
        }
        val displacement = current - target
        val decay = exp(-ANGULAR_FREQUENCY * elapsedSeconds)
        val impulse = (directedVelocity + ANGULAR_FREQUENCY * displacement) * elapsedSeconds
        val nextPosition = target + (displacement + impulse) * decay
        val nextVelocity = ((directedVelocity - ANGULAR_FREQUENCY * impulse) * decay)
            .coerceIn(-MAXIMUM_VELOCITY, MAXIMUM_VELOCITY)
        val movement = nextPosition - current
        if (distance * movement < 0.0) {
            output.position = current
            output.velocity = 0.0
            return
        }
        if (distance * (target - nextPosition) <= 0.0) {
            output.position = target
            output.velocity = 0.0
            return
        }
        output.position = nextPosition
        output.velocity = nextVelocity
    }

    private const val ANGULAR_FREQUENCY = 20.0
    private const val REVERSAL_VELOCITY_RETENTION = 0.25
    private const val MAXIMUM_VELOCITY = 720.0
    private const val SNAP_DISTANCE = 0.02
    private const val SNAP_VELOCITY = 0.25
}

object EmotionPickerListMetrics {
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

    fun fadeRight(listRight: Int): Int = listRight - SCISSOR_INSET
}

object EmotionPickerGridMetrics {
    fun gridWidth(listWidth: Int, scrollbarVisible: Boolean): Int {
        val reservedWidth = if (scrollbarVisible) {
            EmotionPickerListMetrics.SIDE_PADDING +
                EmotionPickerListMetrics.SCROLLBAR_GAP +
                EmotionPickerListMetrics.SCROLLBAR_WIDTH +
                EmotionPickerListMetrics.SCROLLBAR_RIGHT_PADDING
        } else {
            EmotionPickerListMetrics.SIDE_PADDING * 2
        }
        return (listWidth - reservedWidth).coerceAtLeast(EmotionPickerGridLayout.COLUMNS)
    }

    fun cellWidths(listWidth: Int, scrollbarVisible: Boolean): List<Int> {
        val availableWidth = (
            gridWidth(listWidth, scrollbarVisible) -
                (EmotionPickerGridLayout.COLUMNS - 1) * EmotionPickerListMetrics.CELL_GAP
            ).coerceAtLeast(EmotionPickerGridLayout.COLUMNS)
        val baseWidth = (availableWidth / EmotionPickerGridLayout.COLUMNS).coerceAtLeast(1)
        val remainder = (availableWidth - baseWidth * EmotionPickerGridLayout.COLUMNS).coerceAtLeast(0)
        return List(EmotionPickerGridLayout.COLUMNS) { index ->
            baseWidth + if (index < remainder) 1 else 0
        }
    }
}

object EmotionPickerLayoutMetrics {
    const val PANEL_WIDTH = 246
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

object EmotionPickerSideActionLayout {
    const val SIZE = 20
    const val GAP = EmotionPickerVisualMetrics.GAP
    const val STRIDE = SIZE + GAP
    const val RAIL_WIDTH = STRIDE
}

data class EmotionPickerGeometry(
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
        get() = listX + (listWidth - rowWidth) / 2

    val gridWidth: Int
        get() = cellWidths.sum() + (EmotionPickerGridLayout.COLUMNS - 1) * EmotionPickerListMetrics.CELL_GAP

    val sideActionX: Int
        get() = panelX + panelWidth + EmotionPickerSideActionLayout.GAP

    fun sideActionY(index: Int): Int {
        require(index >= 0) { "Side action index must not be negative: $index" }
        return tabY + index * EmotionPickerSideActionLayout.STRIDE
    }

    fun centeredTitleY(lineHeight: Int): Int = titleAreaY + (titleAreaHeight - lineHeight) / 2

    companion object {
        fun calculate(
            screenWidth: Int,
            screenHeight: Int,
            sections: List<EmotionPickerSection>,
        ): EmotionPickerGeometry {
            require(sections.isNotEmpty()) { "Emotion picker requires at least one section" }
            val panelWidth = (
                screenWidth -
                    (EmotionPickerSideActionLayout.RAIL_WIDTH + SIDE_ACTION_VIEWPORT_MARGIN) * 2
                ).coerceIn(MIN_PANEL_WIDTH, EmotionPickerLayoutMetrics.PANEL_WIDTH)
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
            val cellWidths = EmotionPickerGridMetrics.cellWidths(contentWidth, scrollbarVisible = true)
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
        private const val SIDE_ACTION_VIEWPORT_MARGIN = 2
        private const val MIN_PANEL_WIDTH = 128
        private const val MIN_PANEL_HEIGHT = 150
        private const val MAX_PANEL_HEIGHT = 226
        private const val ICON_TAB_WIDTH = 20
        private const val TAB_GAP = EmotionPickerVisualMetrics.GAP
    }
}
