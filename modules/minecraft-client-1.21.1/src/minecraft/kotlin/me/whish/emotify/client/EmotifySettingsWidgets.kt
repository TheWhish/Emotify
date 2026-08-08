package me.whish.emotify.client

import kotlin.math.roundToInt
import me.whish.emotify.client.settings.EmotifySettingsVisualMetrics
import me.whish.emotify.client.settings.EmotifyVolumeLayout
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

internal class EmotifySettingRowButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val label: Component,
    tooltip: Component,
    private val value: () -> Component,
    private val selected: () -> Boolean,
    private val onPressed: () -> Unit,
) : AbstractButton(x, y, width, height, label) {
    private val labelText = CachedFittedText(label.string)
    private val valueText = CachedFittedText("")

    init {
        this.tooltip = Tooltip.create(tooltip)
        refresh()
    }

    override fun onPress() {
        onPressed()
        refresh()
    }

    fun refresh() {
        val currentValue = value()
        valueText.source = currentValue.string
        message = label.copy().append(": ").append(currentValue)
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val selectedNow = selected()
        EmotifySettingsWidgetRenderer.renderButton(guiGraphics, this, selectedNow)
        val font = Minecraft.getInstance().font
        val valueLimit = (width * VALUE_WIDTH_PERCENT / 100).coerceAtLeast(1)
        valueText.fit(font, valueLimit)
        val labelLimit = (
            width - EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING * 2 -
                valueText.pixelWidth - TEXT_GAP
            ).coerceAtLeast(1)
        labelText.fit(font, labelLimit)
        val textY = y + (height - font.lineHeight) / 2 + 1
        val color = if (active) EmotionPickerTheme.labelText else EmotionPickerTheme.mutedText
        guiGraphics.drawString(font, labelText.display, x + HORIZONTAL_PADDING, textY, color, false)
        guiGraphics.drawString(
            font,
            valueText.display,
            right - HORIZONTAL_PADDING - valueText.pixelWidth,
            textY,
            color,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private companion object {
        const val HORIZONTAL_PADDING = 7
        const val TEXT_GAP = 6
        const val VALUE_WIDTH_PERCENT = 45
    }
}

internal class EmotifyVolumeSlider(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    initialVolumePercent: Int,
    private val onChanged: (Int) -> Unit,
) : AbstractSliderButton(
    x,
    y,
    width,
    height,
    Component.empty(),
    initialVolumePercent / 100.0,
) {
    private val label = Component.translatable("screen.emotify.settings.sound_volume")
    private val labelText = CachedFittedText(label.string)
    private val valueText = CachedFittedText("")
    private var volumePercent = initialVolumePercent

    init {
        tooltip = Tooltip.create(Component.translatable("screen.emotify.settings.sound_volume.description"))
        updateMessage()
    }

    override fun updateMessage() {
        val currentValue = Component.translatable("screen.emotify.settings.sound_volume.value", volumePercent)
        valueText.source = currentValue.string
        message = label.copy().append(": ").append(currentValue)
    }

    override fun applyValue() {
        val updated = (value * 100.0).roundToInt().coerceIn(0, 100)
        if (updated == volumePercent) {
            return
        }
        volumePercent = updated
        updateMessage()
        onChanged(updated)
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        EmotifySettingsWidgetRenderer.renderButton(guiGraphics, this, selected = false)
        val font = Minecraft.getInstance().font
        valueText.fit(font, VALUE_MAX_WIDTH)
        val labelLimit = (
            width - EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING * 2 -
                valueText.pixelWidth - TEXT_GAP
            ).coerceAtLeast(1)
        labelText.fit(font, labelLimit)
        val textY = y + EmotifySettingsVisualMetrics.VOLUME_TEXT_TOP
        guiGraphics.drawString(
            font,
            labelText.display,
            x + EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING,
            textY,
            EmotionPickerTheme.labelText,
            false,
        )
        guiGraphics.drawString(
            font,
            valueText.display,
            right - EmotifySettingsVisualMetrics.VOLUME_HORIZONTAL_PADDING - valueText.pixelWidth,
            textY,
            EmotionPickerTheme.labelText,
            false,
        )
        val trackLeft = EmotifyVolumeLayout.trackLeft(x)
        val trackRight = EmotifyVolumeLayout.trackRight(x, width)
        val trackY = bottom - EmotifySettingsVisualMetrics.VOLUME_TRACK_BOTTOM_INSET
        val thumbLeft = EmotifyVolumeLayout.thumbLeft(x, width, value)
        val thumbCenter = thumbLeft + EmotifySettingsVisualMetrics.VOLUME_THUMB_HALF_WIDTH
        guiGraphics.fill(
            trackLeft,
            trackY,
            trackRight,
            trackY + EmotifySettingsVisualMetrics.VOLUME_TRACK_HEIGHT,
            EmotionPickerTheme.sliderTrack,
        )
        guiGraphics.fill(
            trackLeft,
            trackY,
            thumbCenter,
            trackY + EmotifySettingsVisualMetrics.VOLUME_TRACK_HEIGHT,
            EmotionPickerTheme.sliderFill,
        )
        guiGraphics.fill(
            thumbLeft,
            trackY - EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET,
            thumbLeft + EmotifySettingsVisualMetrics.VOLUME_THUMB_WIDTH,
            trackY + EmotifySettingsVisualMetrics.VOLUME_TRACK_HEIGHT +
                EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET,
            EmotionPickerTheme.buttonOutline,
        )
        guiGraphics.fill(
            thumbLeft + 1,
            trackY - EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET + 1,
            thumbLeft + EmotifySettingsVisualMetrics.VOLUME_THUMB_WIDTH - 1,
            trackY + EmotifySettingsVisualMetrics.VOLUME_TRACK_HEIGHT +
                EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET - 1,
            EmotionPickerTheme.sliderThumb,
        )
    }

    override fun createNarrationMessage() = message.copy()

    private companion object {
        const val TEXT_GAP = 6
        const val VALUE_MAX_WIDTH = 38
    }
}

internal class EmotifyTextButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    private val primary: Boolean,
    private val onPressed: () -> Unit,
) : AbstractButton(x, y, width, height, message) {
    private val text = CachedFittedText(message.string)

    override fun onPress() {
        onPressed()
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (primary) {
            EmotionPickerTheme.renderButton(
                guiGraphics,
                x,
                y,
                width,
                height,
                if (isHoveredOrFocused) {
                    EmotionPickerTheme.buttonSelectedHovered
                } else {
                    EmotionPickerTheme.buttonSelected
                },
                EmotionPickerTheme.selectedOutline,
                pressed = false,
            )
        } else {
            EmotifySettingsWidgetRenderer.renderButton(guiGraphics, this, selected = false)
        }
        val font = Minecraft.getInstance().font
        text.fit(font, (width - HORIZONTAL_PADDING * 2).coerceAtLeast(1))
        guiGraphics.drawString(
            font,
            text.display,
            x + (width - text.pixelWidth) / 2,
            y + (height - font.lineHeight) / 2 + 1,
            if (active) EmotionPickerTheme.tabText else EmotionPickerTheme.mutedText,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private companion object {
        const val HORIZONTAL_PADDING = 4
    }
}

internal class EmotifyPlayerRowButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val onPressed: () -> Unit,
) : AbstractButton(x, y, width, height, Component.empty()) {
    private val playerText = CachedFittedText("")
    private val actionText = CachedFittedText("")
    private var ignored = false

    override fun onPress() {
        onPressed()
    }

    fun bind(displayName: String, action: Component, ignored: Boolean) {
        playerText.source = displayName
        actionText.source = action.string
        this.ignored = ignored
        message = Component.literal(displayName).append(": ").append(action)
        tooltip = Tooltip.create(Component.literal(displayName))
        visible = true
        active = true
    }

    fun unbind() {
        visible = false
        active = false
        message = Component.empty()
        tooltip = null
    }

    override fun renderWidget(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        EmotifySettingsWidgetRenderer.renderButton(guiGraphics, this, ignored)
        val font = Minecraft.getInstance().font
        val actionLimit = (width * ACTION_WIDTH_PERCENT / 100).coerceAtLeast(1)
        actionText.fit(font, actionLimit)
        val playerLimit = (
            width - HORIZONTAL_PADDING * 2 - actionText.pixelWidth - TEXT_GAP
            ).coerceAtLeast(1)
        playerText.fit(font, playerLimit)
        val textY = y + (height - font.lineHeight) / 2 + 1
        guiGraphics.drawString(
            font,
            playerText.display,
            x + HORIZONTAL_PADDING,
            textY,
            EmotionPickerTheme.labelText,
            false,
        )
        guiGraphics.drawString(
            font,
            actionText.display,
            right - HORIZONTAL_PADDING - actionText.pixelWidth,
            textY,
            EmotionPickerTheme.labelText,
            false,
        )
    }

    override fun updateWidgetNarration(narrationElementOutput: NarrationElementOutput) {
        defaultButtonNarrationText(narrationElementOutput)
    }

    private companion object {
        const val HORIZONTAL_PADDING = 7
        const val TEXT_GAP = 6
        const val ACTION_WIDTH_PERCENT = 48
    }
}

private object EmotifySettingsWidgetRenderer {
    fun renderButton(
        guiGraphics: GuiGraphics,
        button: AbstractWidget,
        selected: Boolean,
    ) {
        val fill = when {
            !button.active -> EmotionPickerTheme.panel
            selected && button.isHoveredOrFocused -> EmotionPickerTheme.buttonSelectedHovered
            selected -> EmotionPickerTheme.buttonSelected
            button.isHoveredOrFocused -> EmotionPickerTheme.buttonHovered
            else -> EmotionPickerTheme.button
        }
        EmotionPickerTheme.renderButton(
            guiGraphics,
            button.x,
            button.y,
            button.width,
            button.height,
            fill,
            if (selected || button.isFocused) {
                EmotionPickerTheme.selectedOutline
            } else {
                EmotionPickerTheme.buttonOutline
            },
            selected,
        )
    }
}

private class CachedFittedText(initialSource: String) {
    var source: String = initialSource
        set(value) {
            if (field == value) {
                return
            }
            field = value
            fittedSource = ""
        }

    var display: String = initialSource
        private set
    var pixelWidth: Int = 0
        private set
    private var fittedSource = ""
    private var fittedWidth = -1

    fun fit(font: Font, maximumWidth: Int) {
        val safeWidth = maximumWidth.coerceAtLeast(1)
        if (source == fittedSource && safeWidth == fittedWidth) {
            return
        }
        fittedSource = source
        fittedWidth = safeWidth
        val sourceWidth = font.width(source)
        display = if (sourceWidth <= safeWidth) {
            source
        } else {
            val contentWidth = (safeWidth - font.width(TRUNCATION_MARK)).coerceAtLeast(0)
            font.plainSubstrByWidth(source, contentWidth).trimEnd() + TRUNCATION_MARK
        }
        pixelWidth = if (display == source) sourceWidth else font.width(display)
    }

    private companion object {
        const val TRUNCATION_MARK = ".."
    }
}
