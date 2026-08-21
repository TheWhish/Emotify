package me.whish.emotify.client.settings

import kotlin.math.roundToInt
import me.whish.emotify.client.picker.EmotionPickerLayoutMetrics
import me.whish.emotify.client.picker.EmotionPickerVisualMetrics

data class EmotifyUiBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = x + width

    val bottom: Int
        get() = y + height
}

data class EmotifySettingsGeometry(
    val panel: EmotifyUiBounds,
    val list: EmotifyUiBounds,
    val rows: List<EmotifyUiBounds>,
    val cancel: EmotifyUiBounds,
    val done: EmotifyUiBounds,
) {
    fun centeredTitleY(lineHeight: Int): Int =
        panel.y + EmotifySettingsVisualMetrics.TITLE_AREA_TOP +
            (EmotifySettingsVisualMetrics.TITLE_AREA_HEIGHT - lineHeight) / 2
}

data class EmotifyIgnoredPlayersGeometry(
    val panel: EmotifyUiBounds,
    val search: EmotifyUiBounds,
    val list: EmotifyUiBounds,
    val emptyState: EmotifyUiBounds,
    val rowViewport: EmotifyUiBounds,
    val rows: List<EmotifyUiBounds>,
    val previous: EmotifyUiBounds,
    val next: EmotifyUiBounds,
    val cancel: EmotifyUiBounds,
    val done: EmotifyUiBounds,
) {
    fun centeredTitleY(lineHeight: Int): Int =
        panel.y + EmotifySettingsVisualMetrics.TITLE_AREA_TOP +
            (EmotifySettingsVisualMetrics.TITLE_AREA_HEIGHT - lineHeight) / 2
}

object EmotifySettingsVisualMetrics {
    const val SCREEN_MARGIN = 2
    const val GAP = EmotionPickerVisualMetrics.GAP
    const val FRAME_THICKNESS = EmotionPickerVisualMetrics.FRAME_THICKNESS
    const val CONTENT_PADDING = EmotionPickerLayoutMetrics.PANEL_EDGE_PADDING
    const val CONTENT_WIDTH = 234
    const val PANEL_WIDTH = CONTENT_WIDTH + CONTENT_PADDING * 2
    const val PANEL_HEIGHT = 223
    const val IGNORED_PLAYERS_PANEL_HEIGHT = 167
    const val TITLE_AREA_TOP = EmotionPickerLayoutMetrics.TITLE_AREA_TOP
    const val TITLE_AREA_HEIGHT = EmotionPickerLayoutMetrics.TITLE_AREA_HEIGHT
    const val CONTENT_TOP = EmotionPickerLayoutMetrics.TAB_Y_OFFSET
    const val ACTION_HEIGHT = 20
    const val ACTION_GAP = EmotionPickerLayoutMetrics.CONTROL_GAP
    const val SETTINGS_ROW_HEIGHT = 24
    const val SETTINGS_ROW_COUNT = 6
    const val SEARCH_HEIGHT = EmotionPickerLayoutMetrics.SEARCH_FIELD_HEIGHT
    const val PLAYER_LIST_TOP = CONTENT_TOP + SEARCH_HEIGHT + GAP
    const val PLAYER_ROW_HEIGHT = 20
    const val PLAYER_ROWS_PER_PAGE = 3
    const val PLAYER_ROW_STRIDE = PLAYER_ROW_HEIGHT + GAP
    const val NAV_BUTTON_WIDTH = 20
    const val NAV_BUTTON_HEIGHT = 18
    const val VOLUME_HORIZONTAL_PADDING = 7
    const val VOLUME_TEXT_TOP = 4
    const val VOLUME_TRACK_BOTTOM_INSET = 8
    const val VOLUME_TRACK_HEIGHT = 2
    const val VOLUME_THUMB_HALF_WIDTH = 2
    const val VOLUME_THUMB_WIDTH = VOLUME_THUMB_HALF_WIDTH * 2 + 1
    const val VOLUME_THUMB_VERTICAL_INSET = 2
}

object EmotifyVolumeLayout {
    fun trackLeft(x: Int): Int = x + EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING

    fun trackRight(x: Int, width: Int): Int =
        x + width - EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING

    fun thumbLeft(x: Int, width: Int, value: Double): Int {
        require(value in 0.0..1.0) { "Volume slider value is outside the unit interval: $value" }
        val trackLeft = trackLeft(x)
        val travel = (
            trackRight(x, width) - trackLeft - EmotifySettingsVisualMetrics.VOLUME_THUMB_WIDTH
            ).coerceAtLeast(0)
        return trackLeft + (travel * value).roundToInt()
    }
}

object EmotifySettingsLayout {
    fun main(screenWidth: Int, screenHeight: Int): EmotifySettingsGeometry {
        val panel = centeredPanel(
            screenWidth,
            screenHeight,
            EmotifySettingsVisualMetrics.PANEL_HEIGHT,
        )
        val listBottom = footerTop(panel) - EmotifySettingsVisualMetrics.GAP
        val list = EmotifyUiBounds(
            panel.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
            panel.y + EmotifySettingsVisualMetrics.CONTENT_TOP,
            panel.width - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
            listBottom - EmotifySettingsVisualMetrics.CONTENT_TOP,
        )
        val rows = settingsRows(list)
        val actions = actions(panel, list)
        return EmotifySettingsGeometry(panel, list, rows, actions.first, actions.second)
    }

    fun ignoredPlayers(
        screenWidth: Int,
        screenHeight: Int,
    ): EmotifyIgnoredPlayersGeometry {
        val panel = centeredPanel(
            screenWidth,
            screenHeight,
            EmotifySettingsVisualMetrics.IGNORED_PLAYERS_PANEL_HEIGHT,
        )
        val search = EmotifyUiBounds(
            panel.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
            panel.y + EmotifySettingsVisualMetrics.CONTENT_TOP,
            panel.width - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
            EmotifySettingsVisualMetrics.SEARCH_HEIGHT,
        )
        val list = EmotifyUiBounds(
            search.x,
            panel.y + EmotifySettingsVisualMetrics.PLAYER_LIST_TOP,
            search.width,
            footerTop(panel) - EmotifySettingsVisualMetrics.GAP - EmotifySettingsVisualMetrics.PLAYER_LIST_TOP,
        )
        val rows = List(EmotifySettingsVisualMetrics.PLAYER_ROWS_PER_PAGE) { index ->
            EmotifyUiBounds(
                list.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
                list.y + EmotifySettingsVisualMetrics.CONTENT_PADDING +
                    index * EmotifySettingsVisualMetrics.PLAYER_ROW_STRIDE,
                list.width - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
                EmotifySettingsVisualMetrics.PLAYER_ROW_HEIGHT,
            )
        }
        val emptyState = EmotifyUiBounds(
            list.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
            list.y + EmotifySettingsVisualMetrics.CONTENT_PADDING,
            list.width - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
            list.height - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
        )
        val rowViewport = EmotifyUiBounds(
            rows.first().x,
            rows.first().y,
            rows.first().width,
            rows.last().bottom - rows.first().y,
        )
        val navigationY = list.bottom - EmotifySettingsVisualMetrics.FRAME_THICKNESS -
            EmotifySettingsVisualMetrics.NAV_BUTTON_HEIGHT
        val previous = EmotifyUiBounds(
            list.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
            navigationY,
            EmotifySettingsVisualMetrics.NAV_BUTTON_WIDTH,
            EmotifySettingsVisualMetrics.NAV_BUTTON_HEIGHT,
        )
        val next = EmotifyUiBounds(
            list.right - EmotifySettingsVisualMetrics.CONTENT_PADDING -
                EmotifySettingsVisualMetrics.NAV_BUTTON_WIDTH,
            navigationY,
            EmotifySettingsVisualMetrics.NAV_BUTTON_WIDTH,
            EmotifySettingsVisualMetrics.NAV_BUTTON_HEIGHT,
        )
        val actions = actions(panel, list)
        return EmotifyIgnoredPlayersGeometry(
            panel = panel,
            search = search,
            list = list,
            emptyState = emptyState,
            rowViewport = rowViewport,
            rows = rows,
            previous = previous,
            next = next,
            cancel = actions.first,
            done = actions.second,
        )
    }

    private fun centeredPanel(
        screenWidth: Int,
        screenHeight: Int,
        preferredHeight: Int,
    ): EmotifyUiBounds {
        val availableWidth = (screenWidth - EmotifySettingsVisualMetrics.SCREEN_MARGIN * 2).coerceAtLeast(1)
        val availableHeight = (screenHeight - EmotifySettingsVisualMetrics.SCREEN_MARGIN * 2).coerceAtLeast(1)
        val panelWidth = availableWidth.coerceAtMost(EmotifySettingsVisualMetrics.PANEL_WIDTH)
        val panelHeight = availableHeight.coerceAtMost(preferredHeight)
        return EmotifyUiBounds(
            (screenWidth - panelWidth) / 2,
            (screenHeight - panelHeight) / 2,
            panelWidth,
            panelHeight,
        )
    }

    private fun settingsRows(list: EmotifyUiBounds): List<EmotifyUiBounds> {
        val count = EmotifySettingsVisualMetrics.SETTINGS_ROW_COUNT
        val innerHeight = (list.height - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2).coerceAtLeast(1)
        val gap = minOf(
            EmotifySettingsVisualMetrics.GAP,
            ((innerHeight - count).coerceAtLeast(0) / (count - 1)).coerceAtLeast(0),
        )
        val rowHeight = minOf(
            EmotifySettingsVisualMetrics.SETTINGS_ROW_HEIGHT,
            ((innerHeight - gap * (count - 1)) / count).coerceAtLeast(1),
        )
        val occupiedHeight = rowHeight * count + gap * (count - 1)
        val firstY = list.y + EmotifySettingsVisualMetrics.CONTENT_PADDING +
            (innerHeight - occupiedHeight).coerceAtLeast(0) / 2
        return List(count) { index ->
            EmotifyUiBounds(
                list.x + EmotifySettingsVisualMetrics.CONTENT_PADDING,
                firstY + index * (rowHeight + gap),
                list.width - EmotifySettingsVisualMetrics.CONTENT_PADDING * 2,
                rowHeight,
            )
        }
    }

    private fun actions(
        panel: EmotifyUiBounds,
        list: EmotifyUiBounds,
    ): Pair<EmotifyUiBounds, EmotifyUiBounds> {
        val availableWidth = list.width - EmotifySettingsVisualMetrics.ACTION_GAP
        val leftWidth = availableWidth / 2
        val rightWidth = availableWidth - leftWidth
        val y = panel.y + footerTop(panel)
        return EmotifyUiBounds(list.x, y, leftWidth, EmotifySettingsVisualMetrics.ACTION_HEIGHT) to
            EmotifyUiBounds(
                list.x + leftWidth + EmotifySettingsVisualMetrics.ACTION_GAP,
                y,
                rightWidth,
                EmotifySettingsVisualMetrics.ACTION_HEIGHT,
            )
    }

    private fun footerTop(panel: EmotifyUiBounds): Int =
        panel.height - EmotifySettingsVisualMetrics.CONTENT_PADDING - EmotifySettingsVisualMetrics.ACTION_HEIGHT
}

object EmotifySettingsFocusPolicy {
    fun shouldClear(
        childHandled: Boolean,
        mouseButton: Int,
        hasFocusedChild: Boolean,
        focusedChildIsButton: Boolean,
    ): Boolean =
        mouseButton == 0 && hasFocusedChild && (!childHandled || focusedChildIsButton)
}
