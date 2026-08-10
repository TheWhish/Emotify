package me.whish.emotify.client

import kotlin.math.abs
import me.whish.emotify.client.picker.EmotionLabelTruncation
import me.whish.emotify.client.picker.EmotionPickerEdgeFade
import me.whish.emotify.client.picker.EmotionPickerGridLayout
import me.whish.emotify.client.picker.EmotionPickerGridItem
import me.whish.emotify.client.picker.EmotionPickerGridMetrics
import me.whish.emotify.client.picker.EmotionPickerHitArea
import me.whish.emotify.client.picker.EmotionPickerHoverAnimation
import me.whish.emotify.client.picker.EmotionPickerListMetrics
import me.whish.emotify.client.picker.EmotionPickerQuickSlotAnimation
import me.whish.emotify.client.picker.EmotionPickerQuickSlotBounds
import me.whish.emotify.client.picker.EmotionPickerQuickSlotMouseDecision
import me.whish.emotify.client.picker.EmotionPickerQuickSlotMouseRouting
import me.whish.emotify.client.picker.EmotionPickerScrollMath
import me.whish.emotify.client.picker.EmotionPickerScrollbarMetrics
import me.whish.emotify.client.picker.EmotionPickerSideActionLayout
import me.whish.emotify.client.presentation.EmotionPresentation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.sounds.SoundManager
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation

internal enum class EmotionTabIcon {
    NONE,
    FAVORITES,
    SEARCH,
}

internal enum class EmotionPickerSideActionIcon(internal val rows: IntArray) {
    FOLDER(
        intArrayOf(
            0b000000000000,
            0b001111000000,
            0b011111100000,
            0b011111111110,
            0b011111111110,
            0b011111111110,
            0b011111111110,
            0b011111111110,
            0b011111111110,
            0b011111111110,
            0b001111111100,
            0b000000000000,
        ),
    ),
    SETTINGS(
        intArrayOf(
            0b000110011000,
            0b001110011100,
            0b011111111110,
            0b111100001111,
            0b111000000111,
            0b011000000110,
            0b011000000110,
            0b111000000111,
            0b111100001111,
            0b011111111110,
            0b001110011100,
            0b000110011000,
        ),
    ),
}

internal class EmotionSearchBox(
    font: Font,
    private val outerX: Int,
    private val outerY: Int,
    private val outerWidth: Int,
    private val outerHeight: Int,
    message: Component,
) : EditBox(
    font,
    outerX + TEXT_HORIZONTAL_INSET,
    outerY + (outerHeight - font.lineHeight) / 2 + TEXT_VERTICAL_OFFSET,
    outerWidth - TEXT_HORIZONTAL_INSET * 2,
    font.lineHeight,
    message,
) {
    init {
        setBordered(false)
        setTextColor(EmotionPickerTheme.text)
        setTextColorUneditable(EmotionPickerTheme.mutedText)
        EmotionPickerPlatform.configureSearchBox(this)
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        active && visible && EmotionPickerHitArea.contains(
            outerX,
            outerY,
            outerWidth,
            outerHeight,
            mouseX,
            mouseY,
        )

    override fun clicked(mouseX: Double, mouseY: Double): Boolean = isMouseOver(mouseX, mouseY)

    override fun onClick(mouseX: Double, mouseY: Double) {
        super.onClick(mouseX.coerceIn(x.toDouble(), (right - 1).toDouble()), mouseY)
    }

    companion object {
        private const val TEXT_HORIZONTAL_INSET = 5
        private const val TEXT_VERTICAL_OFFSET = 1
    }
}

internal class EmotionGridList(
    minecraft: Minecraft,
    width: Int,
    height: Int,
    y: Int,
    private val rowWidth: Int,
    private val scrollingCellWidths: List<Int>,
    private val onSelected: (EmotionPresentation) -> Unit,
    private val isFavorite: (EmotionPresentation) -> Boolean,
    private val onFavoriteToggled: (EmotionPresentation) -> Unit,
    private val onPointerPressed: (EmotionPresentation, Double, Double, Double, Double) -> Unit,
    private val isDragging: (EmotionPresentation) -> Boolean,
) : ContainerObjectSelectionList<EmotionGridRow>(
    minecraft,
    width,
    height,
    y,
    EmotionPickerGridLayout.ROW_STRIDE,
) {
    private var targetScrollAmount = 0.0
    private var animatedScrollAmount = 0.0
    private var scrollVelocity = 0.0
    private val scrollMotion = EmotionPickerScrollMath.Motion()
    private var lastFrameNanos = 0L
    private var topFadeVisibility = 0.0
    private var bottomFadeVisibility = 0.0
    private var lastFadeFrameNanos = 0L
    private var draggingScrollbar = false
    private var expandedCellWidths = EmotionPickerGridMetrics.cellWidths(width, scrollbarVisible = false)
    private var activeCellWidths = scrollingCellWidths
    private val cellWidthsProvider: () -> List<Int> = { activeCellWidths }

    init {
        setRenderHeader(true, EmotionPickerListMetrics.ROW_HEADER_HEIGHT)
    }

    fun replaceItems(items: List<EmotionPickerGridItem>, resetScroll: Boolean = true) {
        val retainedScroll = targetScrollAmount
        draggingScrollbar = false
        setFocused(null)
        setDragging(false)
        replaceEntries(
            items.chunked(EmotionPickerGridLayout.COLUMNS).map { row ->
                EmotionGridRow(
                    row,
                    cellWidthsProvider,
                    onSelected,
                    isFavorite,
                    onFavoriteToggled,
                    onPointerPressed,
                    isDragging,
                )
            },
        )
        snapToScroll(if (resetScroll) 0.0 else retainedScroll)
    }

    fun setViewport(x: Int, y: Int, width: Int, height: Int) {
        val retainedScroll = targetScrollAmount
        draggingScrollbar = false
        setDragging(false)
        updateSizeAndPosition(width, height, y)
        setX(x)
        expandedCellWidths = EmotionPickerGridMetrics.cellWidths(width, scrollbarVisible = false)
        snapToScroll(retainedScroll)
    }

    override fun getRowWidth(): Int = rowWidth

    override fun getScrollbarPosition(): Int = right - EmotionPickerListMetrics.SCROLLBAR_RIGHT_INSET

    override fun enableScissor(guiGraphics: GuiGraphics) {
        guiGraphics.enableScissor(
            x + 1,
            y + EmotionPickerListMetrics.SCISSOR_INSET,
            right - 1,
            bottom - EmotionPickerListMetrics.SCISSOR_INSET,
        )
    }

    override fun getMaxPosition(): Int = super.getMaxPosition() + EmotionPickerListMetrics.ROW_HEADER_HEIGHT

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        advanceScrollAnimation()
        activeCellWidths = if (scrollbarVisible()) scrollingCellWidths else expandedCellWidths
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        if (!isMouseOver(mouseX, mouseY) || getMaxScroll() == 0) {
            return false
        }
        targetScrollAmount = clampScroll(targetScrollAmount - scrollY * WHEEL_SCROLL_DISTANCE)
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true
            setDragging(true)
            return true
        }
        if (!isInsideContent(mouseY)) {
            return false
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        if (button == 0 && draggingScrollbar) {
            val travel = scrollbarTravel().coerceAtLeast(1)
            snapToScroll(
                EmotionPickerScrollMath.draggedAmount(
                    scrollAmount,
                    dragY,
                    getMaxScroll().toDouble(),
                    travel,
                ),
            )
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false
            setDragging(false)
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun renderListBackground(guiGraphics: GuiGraphics) {
        EmotionPickerTheme.renderList(guiGraphics, x, y, width, height)
    }

    override fun renderListSeparators(guiGraphics: GuiGraphics) {
    }

    override fun renderDecorations(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (scrollbarVisible()) {
            renderScrollbar(guiGraphics)
        }
        renderEdgeFades(guiGraphics)
    }

    private fun renderScrollbar(guiGraphics: GuiGraphics) {
        val scrollbarX = getScrollbarPosition()
        val scrollbarTop = y + EmotionPickerListMetrics.EDGE_PADDING
        val scrollbarBottom = bottom - EmotionPickerListMetrics.EDGE_PADDING
        val gutterLeft = scrollbarX - EmotionPickerListMetrics.SCROLLBAR_GAP
        guiGraphics.fill(gutterLeft, y, right - 1, bottom, EmotionPickerTheme.list)
        EmotionPickerTheme.restoreListGutterFrame(guiGraphics, gutterLeft, y, right, bottom)
        val trackX = scrollbarX + EmotionPickerListMetrics.SCROLLBAR_INNER_MARGIN
        EmotionPickerTheme.renderScrollbarTrack(
            guiGraphics,
            trackX,
            scrollbarTop,
            EmotionPickerListMetrics.SCROLLBAR_TRACK_WIDTH,
            scrollbarBottom - scrollbarTop,
        )
        val thumbHeight = scrollbarThumbHeight()
        val thumbY = (scrollAmount * scrollbarTravel() / getMaxScroll() + scrollbarTop)
            .toInt()
            .coerceIn(scrollbarTop, scrollbarBottom - thumbHeight)
        EmotionPickerTheme.renderScrollbarThumb(
            guiGraphics,
            scrollbarX,
            thumbY,
            EmotionPickerListMetrics.SCROLLBAR_WIDTH,
            thumbHeight,
        )
    }

    private fun isOverScrollbar(mouseX: Double, mouseY: Double): Boolean {
        val scrollbarX = getScrollbarPosition()
        return scrollbarVisible() &&
            mouseX >= scrollbarX &&
            mouseX < scrollbarX + EmotionPickerListMetrics.SCROLLBAR_WIDTH &&
            isInsideContent(mouseY)
    }

    private fun isInsideContent(mouseY: Double): Boolean =
        mouseY >= y + EmotionPickerListMetrics.EDGE_PADDING &&
            mouseY < bottom - EmotionPickerListMetrics.EDGE_PADDING

    private fun scrollbarThumbHeight(): Int {
        val trackHeight = height - EmotionPickerListMetrics.EDGE_PADDING * 2
        return EmotionPickerScrollbarMetrics.thumbHeight(trackHeight, getMaxPosition())
    }

    private fun scrollbarTravel(): Int {
        val trackHeight = height - EmotionPickerListMetrics.EDGE_PADDING * 2
        return trackHeight - scrollbarThumbHeight()
    }

    private fun snapToScroll(scroll: Double) {
        val clamped = clampScroll(scroll)
        targetScrollAmount = clamped
        animatedScrollAmount = clamped
        scrollVelocity = 0.0
        super.setScrollAmount(clamped)
        lastFrameNanos = 0L
    }

    fun retainedScrollAmount(): Double = targetScrollAmount

    fun restoreScrollAmount(scroll: Double) {
        snapToScroll(scroll)
    }

    private fun clampScroll(scroll: Double): Double = scroll.coerceIn(0.0, getMaxScroll().toDouble())

    private fun advanceScrollAnimation() {
        val currentScroll = scrollAmount
        if (abs(currentScroll - animatedScrollAmount) > EXTERNAL_SCROLL_EPSILON) {
            animatedScrollAmount = currentScroll
            targetScrollAmount = currentScroll
            scrollVelocity = 0.0
        }
        targetScrollAmount = clampScroll(targetScrollAmount)
        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
            return
        }
        val elapsedSeconds = ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
        lastFrameNanos = now
        EmotionPickerScrollMath.advance(
            animatedScrollAmount,
            targetScrollAmount,
            scrollVelocity,
            elapsedSeconds,
            scrollMotion,
        )
        animatedScrollAmount = scrollMotion.position
        scrollVelocity = scrollMotion.velocity
        super.setScrollAmount(animatedScrollAmount)
    }

    private fun renderEdgeFades(guiGraphics: GuiGraphics) {
        if (!scrollbarVisible()) {
            topFadeVisibility = 0.0
            bottomFadeVisibility = 0.0
            lastFadeFrameNanos = 0L
            return
        }
        advanceEdgeFadeAnimation()
        val fadeLeft = EmotionPickerListMetrics.fadeLeft(x)
        val fadeRight = EmotionPickerListMetrics.fadeRight(right)
        guiGraphics.enableScissor(
            x + 1,
            y + EmotionPickerListMetrics.SCISSOR_INSET,
            right - 1,
            bottom - EmotionPickerListMetrics.SCISSOR_INSET,
        )
        try {
            if (topFadeVisibility > MINIMUM_FADE_VISIBILITY) {
                renderFade(
                    guiGraphics,
                    fadeLeft,
                    fadeRight,
                    y + EmotionPickerListMetrics.SCISSOR_INSET,
                    topFadeVisibility,
                    true,
                )
            }
            if (bottomFadeVisibility > MINIMUM_FADE_VISIBILITY) {
                renderFade(
                    guiGraphics,
                    fadeLeft,
                    fadeRight,
                    bottom - EmotionPickerListMetrics.SCISSOR_INSET,
                    bottomFadeVisibility,
                    false,
                )
            }
        } finally {
            guiGraphics.disableScissor()
        }
    }

    private fun renderFade(
        guiGraphics: GuiGraphics,
        left: Int,
        right: Int,
        edgeY: Int,
        visibility: Double,
        downward: Boolean,
    ) {
        repeat(EmotionPickerEdgeFade.HEIGHT) { offset ->
            val alpha = EmotionPickerEdgeFade.alphaAt(offset, visibility)
            if (alpha == 0) {
                return@repeat
            }
            val color = alpha shl 24 or (EmotionPickerTheme.edgeFade and 0x00FFFFFF)
            val lineY = if (downward) edgeY + offset else edgeY - offset - 1
            guiGraphics.fill(left, lineY, right, lineY + 1, color)
        }
    }

    private fun advanceEdgeFadeAnimation() {
        val now = System.nanoTime()
        if (lastFadeFrameNanos == 0L) {
            lastFadeFrameNanos = now
            return
        }
        val elapsedSeconds = ((now - lastFadeFrameNanos) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
        lastFadeFrameNanos = now
        topFadeVisibility = EmotionPickerEdgeFade.nextVisibility(
            topFadeVisibility,
            EmotionPickerEdgeFade.targetVisibility(scrollAmount),
            elapsedSeconds,
        )
        bottomFadeVisibility = EmotionPickerEdgeFade.nextVisibility(
            bottomFadeVisibility,
            EmotionPickerEdgeFade.targetVisibility(getMaxScroll() - scrollAmount),
            elapsedSeconds,
        )
    }

    companion object {
        private const val WHEEL_SCROLL_DISTANCE = 32.0
        private const val EXTERNAL_SCROLL_EPSILON = 0.1
        private const val MAX_FRAME_SECONDS = 0.05
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MINIMUM_FADE_VISIBILITY = 0.002
    }
}

internal class EmotionGridRow(
    items: List<EmotionPickerGridItem>,
    private val cellWidths: () -> List<Int>,
    onSelected: (EmotionPresentation) -> Unit,
    isFavorite: (EmotionPresentation) -> Boolean,
    onFavoriteToggled: (EmotionPresentation) -> Unit,
    onPointerPressed: (EmotionPresentation, Double, Double, Double, Double) -> Unit,
    isDragging: (EmotionPresentation) -> Boolean,
) : ContainerObjectSelectionList.Entry<EmotionGridRow>() {
    private val cells: List<EmotionGridCell> = java.util.List.copyOf(
        items.map { item ->
            when (item) {
                is EmotionPickerGridItem.Available -> EmotionGridCell.Available(
                    EmotionIconButton(
                        item.presentation,
                        onSelected,
                        onPointerPressed,
                        { isDragging(item.presentation) },
                    ),
                    FavoriteButton(
                        item.presentation,
                        { isFavorite(item.presentation) },
                        { onFavoriteToggled(item.presentation) },
                    ),
                )
                is EmotionPickerGridItem.UnavailableCustom -> EmotionGridCell.Unavailable(
                    UnavailableCustomEmojiWidget(item),
                )
            }
        },
    )
    private val children: List<GuiEventListener> = java.util.List.copyOf(
        cells.flatMap { cell ->
            when (cell) {
                is EmotionGridCell.Available -> listOf(cell.favoriteButton, cell.emotionButton)
                is EmotionGridCell.Unavailable -> listOf(cell.widget)
            }
        },
    )
    private val narratables: List<NarratableEntry> = java.util.List.copyOf(
        cells.flatMap { cell ->
            when (cell) {
                is EmotionGridCell.Available -> listOf(cell.favoriteButton, cell.emotionButton)
                is EmotionGridCell.Unavailable -> listOf(cell.widget)
            }
        },
    )

    override fun children(): List<GuiEventListener> = children

    override fun narratables(): List<NarratableEntry> = narratables

    override fun render(
        guiGraphics: GuiGraphics,
        index: Int,
        top: Int,
        left: Int,
        width: Int,
        height: Int,
        mouseX: Int,
        mouseY: Int,
        hovering: Boolean,
        partialTick: Float,
    ) {
        var cellX = left - EmotionPickerListMetrics.VANILLA_ROW_BIAS
        val currentCellWidths = cellWidths()
        cells.forEachIndexed { column, cell ->
            val cellWidth = currentCellWidths[column]
            when (cell) {
                is EmotionGridCell.Available -> {
                    cell.emotionButton.x = cellX
                    cell.emotionButton.y = top
                    cell.emotionButton.width = cellWidth
                    cell.emotionButton.height = EmotionPickerGridLayout.CELL_HEIGHT
                    cell.favoriteButton.x = cellX + cellWidth - FavoriteButton.SIZE - FAVORITE_RIGHT_INSET
                    cell.favoriteButton.y = top + FAVORITE_TOP_INSET
                    cell.emotionButton.render(guiGraphics, mouseX, mouseY, partialTick)
                    cell.favoriteButton.render(guiGraphics, mouseX, mouseY, partialTick)
                }
                is EmotionGridCell.Unavailable -> {
                    cell.widget.x = cellX
                    cell.widget.y = top
                    cell.widget.width = cellWidth
                    cell.widget.height = EmotionPickerGridLayout.CELL_HEIGHT
                    cell.widget.render(guiGraphics, mouseX, mouseY, partialTick)
                }
            }
            cellX += cellWidth + EmotionPickerListMetrics.CELL_GAP
        }
    }

    companion object {
        private const val FAVORITE_RIGHT_INSET = 2
        private const val FAVORITE_TOP_INSET = 2
    }
}

private sealed interface EmotionGridCell {
    data class Available(
        val emotionButton: EmotionIconButton,
        val favoriteButton: FavoriteButton,
    ) : EmotionGridCell

    data class Unavailable(val widget: UnavailableCustomEmojiWidget) : EmotionGridCell
}

private object EmotionGridCardMetrics {
    const val ICON_SIZE = 16
    const val ICON_Y_OFFSET = 4
    const val LABEL_Y_OFFSET = 23
    const val LABEL_PADDING = 4
}

private class UnavailableCustomEmojiWidget(
    item: EmotionPickerGridItem.UnavailableCustom,
) : AbstractWidget(
    0,
    0,
    1,
    EmotionPickerGridLayout.CELL_HEIGHT,
    Component.translatable(
        "screen.emotify.custom_error.tooltip",
        item.diagnostic.displayName,
        Component.translatable(item.diagnostic.reason.translationKey),
    ),
) {
    private val displayName = item.diagnostic.displayName

    init {
        tooltip = Tooltip.create(message)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = false

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        EmotionPickerTheme.renderUnavailableCard(guiGraphics, x, y, width, height)
        EmotionPickerTheme.renderUnavailablePreview(
            guiGraphics,
            x + (width - EmotionGridCardMetrics.ICON_SIZE) / 2,
            y + EmotionGridCardMetrics.ICON_Y_OFFSET,
            EmotionGridCardMetrics.ICON_SIZE,
        )
        val font = Minecraft.getInstance().font
        val label = fittedText(
            font,
            displayName,
            (width - EmotionGridCardMetrics.LABEL_PADDING * 2).coerceAtLeast(0),
        )
        guiGraphics.drawString(
            font,
            label,
            x + (width - font.width(label)) / 2,
            y + EmotionGridCardMetrics.LABEL_Y_OFFSET,
            EmotionPickerTheme.unavailableText,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        narrationElementOutput.add(NarratedElementType.TITLE, message)
    }
}

private fun fittedText(font: Font, value: String, availableWidth: Int): String {
    if (font.width(value) <= availableWidth) {
        return value
    }
    val shortenedWidth = (availableWidth - font.width(DIAGNOSTIC_TRUNCATION_MARK)).coerceAtLeast(0)
    val shortened = font.plainSubstrByWidth(value, shortenedWidth)
    return EmotionLabelTruncation.completePrefix(value, shortened) + DIAGNOSTIC_TRUNCATION_MARK
}

private const val DIAGNOSTIC_TRUNCATION_MARK = ".."

private class EmotionIconButton(
    private val presentation: EmotionPresentation,
    private val onSelected: (EmotionPresentation) -> Unit,
    private val onPointerPressed: (EmotionPresentation, Double, Double, Double, Double) -> Unit,
    private val isDragging: () -> Boolean,
) : AbstractButton(
    0,
    0,
    1,
    EmotionPickerGridLayout.CELL_HEIGHT,
    presentation.nameComponent(),
) {
    private val texture = EmotionTextureResources.resolve(presentation.textureId)
    private val titleTooltip = Tooltip.create(message)
    private val resolvedLabel = message.string
    private var cachedLabelWidth = -1
    private var cachedLabel = ""
    private var cachedLabelPixelWidth = 0
    private val hoverMotion = EmotionPickerHoverAnimation.Motion()

    init {
        tooltip = titleTooltip
    }

    override fun onPress() {
        onSelected(presentation)
    }

    override fun onClick(mouseX: Double, mouseY: Double) {
        onPointerPressed(
            presentation,
            mouseX,
            mouseY,
            x + width / 2.0,
            y + EmotionGridCardMetrics.ICON_Y_OFFSET + EmotionGridCardMetrics.ICON_SIZE / 2.0,
        )
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        super.isMouseOver(mouseX, mouseY) && !isOverFavorite(mouseX.toInt(), mouseY.toInt())

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val overFavorite = isOverFavorite(mouseX, mouseY)
        tooltip = if (overFavorite) null else titleTooltip
        val hovered = isHoveredOrFocused && !overFavorite
        val dragging = isDragging()
        val hoverEmphasis = hoverMotion.advance(hovered, System.nanoTime())
        EmotionPickerTheme.renderButton(
            guiGraphics,
            x,
            y,
            width,
            height,
            when {
                dragging -> EmotionPickerTheme.buttonSelected
                else -> EmotionPickerTheme.blendColor(
                    EmotionPickerTheme.button,
                    EmotionPickerTheme.buttonHovered,
                    hoverEmphasis,
                )
            },
            if (dragging) EmotionPickerTheme.selectedOutline else EmotionPickerTheme.buttonOutline,
            dragging,
        )
        val region = presentation.regionAt(System.nanoTime() / 1_000_000L)
        guiGraphics.blit(
            texture,
            x + (width - EmotionGridCardMetrics.ICON_SIZE) / 2,
            y + EmotionGridCardMetrics.ICON_Y_OFFSET,
            EmotionGridCardMetrics.ICON_SIZE,
            EmotionGridCardMetrics.ICON_SIZE,
            region.x.toFloat(),
            region.y.toFloat(),
            region.width,
            region.height,
            region.textureWidth,
            region.textureHeight,
        )
        val font = Minecraft.getInstance().font
        val availableLabelWidth = width - EmotionGridCardMetrics.LABEL_PADDING * 2
        updateFittedLabel(font, availableLabelWidth)
        guiGraphics.drawString(
            font,
            cachedLabel,
            x + (width - cachedLabelPixelWidth) / 2,
            y + EmotionGridCardMetrics.LABEL_Y_OFFSET,
            EmotionPickerTheme.labelText,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private fun isOverFavorite(mouseX: Int, mouseY: Int): Boolean {
        val favoriteX = x + width - FavoriteButton.SIZE - FAVORITE_RIGHT_INSET
        val favoriteY = y + FAVORITE_TOP_INSET
        return mouseX >= favoriteX &&
            mouseX < favoriteX + FavoriteButton.SIZE &&
            mouseY >= favoriteY &&
            mouseY < favoriteY + FavoriteButton.SIZE
    }

    private fun updateFittedLabel(font: Font, availableWidth: Int) {
        if (cachedLabelWidth == availableWidth) {
            return
        }
        cachedLabelWidth = availableWidth
        val sourceWidth = font.width(resolvedLabel)
        cachedLabel = if (sourceWidth <= availableWidth) {
            resolvedLabel
        } else {
            val shortenedWidth = (availableWidth - font.width(TRUNCATION_MARK)).coerceAtLeast(0)
            val shortened = font.plainSubstrByWidth(resolvedLabel, shortenedWidth)
            EmotionLabelTruncation.completePrefix(resolvedLabel, shortened) + TRUNCATION_MARK
        }
        cachedLabelPixelWidth = if (cachedLabel == resolvedLabel) sourceWidth else font.width(cachedLabel)
    }

    companion object {
        private const val FAVORITE_RIGHT_INSET = 2
        private const val FAVORITE_TOP_INSET = 2
        private const val TRUNCATION_MARK = ".."
    }
}

internal class EmotionQuickSlotButton(
    private val slotIndex: Int,
    bounds: EmotionPickerQuickSlotBounds,
    private val assigned: () -> Boolean,
    private val presentation: () -> EmotionPresentation?,
    private val dropTarget: () -> Boolean,
    private val nowNanos: () -> Long,
    private val reducedMotion: () -> Boolean,
    private val onActivated: (Int) -> Unit,
    private val onCleared: (Int) -> Unit,
) : AbstractButton(
    bounds.x,
    bounds.y,
    bounds.width,
    bounds.height,
    Component.translatable("screen.emotify.quick_slot", slotIndex + 1),
) {
    private val previewSize = bounds.previewSize
    private val slotLabel = (slotIndex + 1).toString()
    private var landingStartedNanos = Long.MIN_VALUE
    private var targetEmphasis = 0.0
    private var lastTargetFrameNanos = Long.MIN_VALUE
    private val hoverMotion = EmotionPickerHoverAnimation.Motion()
    private var tooltipAssigned: Boolean? = null
    private var tooltipPresentation: EmotionPresentation? = null

    override fun onPress() {
        if (assigned()) {
            onActivated(slotIndex)
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        return when (
            EmotionPickerQuickSlotMouseRouting.click(
                assigned(),
                active && visible && isMouseOver(mouseX, mouseY),
                button,
            )
        ) {
            EmotionPickerQuickSlotMouseDecision.DISPATCH -> false
            EmotionPickerQuickSlotMouseDecision.CONSUME_EMPTY -> true
            EmotionPickerQuickSlotMouseDecision.ACTIVATE -> super.mouseClicked(mouseX, mouseY, button)
            EmotionPickerQuickSlotMouseDecision.CLEAR -> {
                onCleared(slotIndex)
                true
            }
        }
    }

    fun startLanding(startedNanos: Long) {
        landingStartedNanos = startedNanos
    }

    fun activateFromKeyboard(soundManager: SoundManager) {
        if (assigned()) {
            playDownSound(soundManager)
        }
        onActivated(slotIndex)
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val now = nowNanos()
        val assignedNow = assigned()
        val presentationNow = presentation()
        updateTooltip(assignedNow, presentationNow)
        val targeted = dropTarget()
        updateTargetEmphasis(targeted, now)
        val hoverEmphasis = hoverMotion.advance(isHoveredOrFocused, now)
        if (!assignedNow) {
            EmotionPickerTheme.renderEmptySlot(
                guiGraphics,
                x,
                y,
                width,
                height,
                hoverEmphasis,
                targetEmphasis,
            )
            renderEmptyLabel(guiGraphics)
            return
        }
        val landingElapsed = if (landingStartedNanos == Long.MIN_VALUE) {
            EmotionPickerQuickSlotAnimation.DURATION_NANOS
        } else {
            (now - landingStartedNanos).coerceAtLeast(0L)
        }
        val landing = EmotionPickerQuickSlotAnimation.isLanding(landingElapsed)
        if (!landing) {
            landingStartedNanos = Long.MIN_VALUE
        }
        val landingEmphasis = if (landing) {
            EmotionPickerQuickSlotAnimation.landingEmphasis(landingElapsed)
        } else {
            0.0
        }
        val baseFill = EmotionPickerTheme.blendColor(
            EmotionPickerTheme.button,
            EmotionPickerTheme.buttonHovered,
            hoverEmphasis,
        )
        val landingFill = EmotionPickerTheme.blendColor(
            baseFill,
            EmotionPickerTheme.buttonSelected,
            landingEmphasis,
        )
        val fill = EmotionPickerTheme.blendColor(
            landingFill,
            EmotionPickerTheme.buttonSelectedHovered,
            targetEmphasis,
        )
        val outlineEmphasis = maxOf(landingEmphasis, targetEmphasis)
        EmotionPickerTheme.renderButton(
            guiGraphics,
            x,
            y,
            width,
            height,
            fill,
            EmotionPickerTheme.blendColor(
                EmotionPickerTheme.buttonOutline,
                EmotionPickerTheme.selectedOutline,
                outlineEmphasis,
            ),
            false,
        )
        val motionReduced = reducedMotion()
        val landingOffset = if (landing && !motionReduced) {
            EmotionPickerQuickSlotAnimation.landingOffset(landingElapsed)
        } else {
            0.0
        }
        val landingScale = if (landing && !motionReduced) {
            EmotionPickerQuickSlotAnimation.landingScale(landingElapsed)
        } else {
            1.0
        }
        if (presentationNow == null) {
            val font = Minecraft.getInstance().font
            guiGraphics.drawString(
                font,
                "?",
                x + (width - font.width("?")) / 2,
                y + (height - font.lineHeight) / 2 + 1,
                EmotionPickerTheme.mutedText,
                false,
            )
            return
        }
        val region = presentationNow.regionAt(System.nanoTime() / 1_000_000L)
        val pose = guiGraphics.pose()
        pose.pushPose()
        try {
            pose.translate(x + width / 2.0, y + height / 2.0 + landingOffset, 0.0)
            pose.scale(landingScale.toFloat(), landingScale.toFloat(), 1.0F)
            pose.translate(-previewSize / 2.0, -previewSize / 2.0, 0.0)
            guiGraphics.blit(
                EmotionTextureResources.resolve(presentationNow.textureId),
                0,
                0,
                previewSize,
                previewSize,
                region.x.toFloat(),
                region.y.toFloat(),
                region.width,
                region.height,
                region.textureWidth,
                region.textureHeight,
            )
        } finally {
            pose.popPose()
        }
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private fun renderEmptyLabel(guiGraphics: GuiGraphics) {
        val font = Minecraft.getInstance().font
        guiGraphics.drawString(
            font,
            slotLabel,
            x + (width - font.width(slotLabel)) / 2,
            y + (height - font.lineHeight) / 2 + 1,
            EmotionPickerTheme.mutedText,
            false,
        )
    }

    private fun updateTooltip(assignedNow: Boolean, presentationNow: EmotionPresentation?) {
        if (tooltipAssigned == assignedNow && tooltipPresentation === presentationNow) {
            return
        }
        tooltipAssigned = assignedNow
        tooltipPresentation = presentationNow
        tooltip = Tooltip.create(
            when {
                presentationNow != null -> Component.translatable(
                    "screen.emotify.quick_slot.filled_tooltip",
                    slotIndex + 1,
                    presentationNow.nameComponent(),
                )
                assignedNow -> Component.translatable(
                    "screen.emotify.quick_slot.unavailable_tooltip",
                    slotIndex + 1,
                )
                else -> Component.translatable(
                    "screen.emotify.quick_slot.empty_tooltip",
                    slotIndex + 1,
                )
            },
        )
    }

    private fun updateTargetEmphasis(targeted: Boolean, now: Long) {
        if (lastTargetFrameNanos == Long.MIN_VALUE) {
            lastTargetFrameNanos = now
            return
        }
        if (targetEmphasis == if (targeted) 1.0 else 0.0) {
            lastTargetFrameNanos = now
            return
        }
        val elapsedSeconds = ((now - lastTargetFrameNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
            .coerceAtMost(MAXIMUM_TARGET_FRAME_SECONDS)
        lastTargetFrameNanos = now
        targetEmphasis = EmotionPickerQuickSlotAnimation.nextTargetEmphasis(
            targetEmphasis,
            targeted,
            elapsedSeconds,
        )
    }

    private companion object {
        const val MAXIMUM_TARGET_FRAME_SECONDS = 0.05
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

internal class EmotionTabButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    private val icon: EmotionTabIcon,
    private val selected: () -> Boolean,
    private val onPressed: () -> Unit,
) : AbstractButton(x, y, width, height, message) {
    private val fullTooltip = Tooltip.create(message)
    private val resolvedLabel = message.string
    private var cachedLabelWidth = -1
    private var cachedLabel = ""
    private var cachedLabelPixelWidth = 0
    private val hoverMotion = EmotionPickerHoverAnimation.Motion()

    init {
        if (icon != EmotionTabIcon.NONE) {
            tooltip = fullTooltip
        }
    }

    override fun onPress() {
        onPressed()
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val selectedNow = selected()
        val hoverEmphasis = hoverMotion.advance(isHoveredOrFocused, System.nanoTime())
        val background = when {
            selectedNow -> EmotionPickerTheme.buttonSelected
            else -> EmotionPickerTheme.blendColor(
                EmotionPickerTheme.button,
                EmotionPickerTheme.buttonHovered,
                hoverEmphasis,
            )
        }
        EmotionPickerTheme.renderButton(
            guiGraphics,
            x,
            y,
            width,
            height,
            background,
            if (selectedNow) EmotionPickerTheme.selectedOutline else EmotionPickerTheme.buttonOutline,
            selectedNow,
        )
        when (icon) {
            EmotionTabIcon.NONE -> renderLabel(guiGraphics)
            EmotionTabIcon.FAVORITES -> renderFavoriteIcon(guiGraphics)
            EmotionTabIcon.SEARCH -> renderSearchIcon(guiGraphics)
        }
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private fun renderLabel(guiGraphics: GuiGraphics) {
        val font = Minecraft.getInstance().font
        val availableWidth = (width - LABEL_HORIZONTAL_PADDING * 2).coerceAtLeast(1)
        if (cachedLabelWidth != availableWidth) {
            cachedLabelWidth = availableWidth
            val sourceWidth = font.width(resolvedLabel)
            cachedLabel = if (sourceWidth <= availableWidth) {
                resolvedLabel
            } else {
                val contentWidth = (availableWidth - font.width(TRUNCATION_MARK)).coerceAtLeast(0)
                font.plainSubstrByWidth(resolvedLabel, contentWidth).trimEnd() + TRUNCATION_MARK
            }
            cachedLabelPixelWidth = if (cachedLabel == resolvedLabel) sourceWidth else font.width(cachedLabel)
            tooltip = if (cachedLabel == resolvedLabel) null else fullTooltip
        }
        guiGraphics.drawString(
            font,
            cachedLabel,
            x + (width - cachedLabelPixelWidth) / 2,
            y + (height - font.lineHeight) / 2 + 1,
            EmotionPickerTheme.tabText,
            false,
        )
    }

    private fun renderFavoriteIcon(guiGraphics: GuiGraphics) {
        val font = Minecraft.getInstance().font
        guiGraphics.drawString(
            font,
            FILLED_STAR,
            x + (width - font.width(FILLED_STAR)) / 2,
            y + (height - font.lineHeight) / 2 + 1,
            EmotionPickerTheme.favorite,
            false,
        )
    }

    private fun renderSearchIcon(guiGraphics: GuiGraphics) {
        guiGraphics.blitSprite(
            SEARCH_ICON,
            x + (width - SEARCH_ICON_SIZE) / 2,
            y + (height - SEARCH_ICON_SIZE) / 2,
            SEARCH_ICON_SIZE,
            SEARCH_ICON_SIZE,
        )
    }

    companion object {
        private val SEARCH_ICON = ResourceLocation.withDefaultNamespace("icon/search")
        private const val SEARCH_ICON_SIZE = 12
        private const val LABEL_HORIZONTAL_PADDING = 4
        private const val TRUNCATION_MARK = ".."
        private const val FILLED_STAR = "★"
    }
}

internal class EmotionPickerSideActionButton(
    x: Int,
    y: Int,
    message: Component,
    private val icon: EmotionPickerSideActionIcon,
    private val onPressed: () -> Unit,
) : AbstractButton(
    x,
    y,
    EmotionPickerSideActionLayout.SIZE,
    EmotionPickerSideActionLayout.SIZE,
    message,
) {
    private val hoverMotion = EmotionPickerHoverAnimation.Motion()

    init {
        tooltip = Tooltip.create(message)
    }

    override fun onPress() {
        onPressed()
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val hoverEmphasis = hoverMotion.advance(isHoveredOrFocused, System.nanoTime())
        EmotionPickerTheme.renderButton(
            guiGraphics,
            x,
            y,
            width,
            height,
            EmotionPickerTheme.blendColor(
                EmotionPickerTheme.button,
                EmotionPickerTheme.buttonHovered,
                hoverEmphasis,
            ),
            EmotionPickerTheme.buttonOutline,
            false,
        )
        renderIcon(guiGraphics)
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private fun renderIcon(guiGraphics: GuiGraphics) {
        val left = x + (width - ICON_SIZE) / 2
        val top = y + (height - ICON_SIZE) / 2
        for (row in icon.rows.indices) {
            val mask = icon.rows[row]
            var column = 0
            while (column < ICON_SIZE) {
                while (column < ICON_SIZE && !isSet(mask, column)) {
                    column++
                }
                val start = column
                while (column < ICON_SIZE && isSet(mask, column)) {
                    column++
                }
                if (start < column) {
                    guiGraphics.fill(
                        left + start,
                        top + row,
                        left + column,
                        top + row + 1,
                        EmotionPickerTheme.tabText,
                    )
                }
            }
        }
    }

    private fun isSet(mask: Int, column: Int): Boolean =
        mask and (1 shl (ICON_SIZE - column - 1)) != 0

    companion object {
        private const val ICON_SIZE = 12
    }
}

private class FavoriteButton(
    private val presentation: EmotionPresentation,
    private val isFavorite: () -> Boolean,
    private val onToggled: () -> Unit,
) : AbstractButton(0, 0, SIZE, SIZE, Component.empty()) {
    private var favorite = isFavorite()
    private val hoverMotion = EmotionPickerHoverAnimation.Motion()

    init {
        updateAction()
    }

    override fun onPress() {
        onToggled()
        synchronizeState()
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        synchronizeState()
        val hoverEmphasis = hoverMotion.advance(isHovered, System.nanoTime())
        if (hoverEmphasis > 0.0) {
            guiGraphics.fill(
                x,
                y,
                right,
                bottom,
                EmotionPickerTheme.colorWithOpacity(
                    HOVER_BACKGROUND_COLOR,
                    (hoverEmphasis * 255.0 + 0.5).toInt(),
                ),
            )
        }
        val font = Minecraft.getInstance().font
        val symbol = if (favorite) FILLED_STAR else EMPTY_STAR
        guiGraphics.drawString(
            font,
            symbol,
            x + (width - font.width(symbol)) / 2,
            y + 2,
            if (favorite) EmotionPickerTheme.favorite else EmotionPickerTheme.mutedText,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private fun synchronizeState() {
        val current = isFavorite()
        if (current == favorite) {
            return
        }
        favorite = current
        updateAction()
    }

    private fun updateAction() {
        val action = Component.translatable(
            if (favorite) "screen.emotify.remove_favorite" else "screen.emotify.add_favorite",
            presentation.nameComponent(),
        )
        message = action
        tooltip = Tooltip.create(action)
    }

    companion object {
        const val SIZE = 12

        private const val FILLED_STAR = "★"
        private const val EMPTY_STAR = "☆"
        private const val HOVER_BACKGROUND_COLOR = 0x55FFFFFF
    }
}

private fun EmotionPresentation.nameComponent(): Component =
    literalName?.let(Component::literal) ?: Component.translatable(translationKey)
