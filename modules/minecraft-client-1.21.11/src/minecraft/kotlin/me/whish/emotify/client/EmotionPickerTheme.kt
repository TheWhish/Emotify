package me.whish.emotify.client

import net.minecraft.client.gui.GuiGraphics

internal object EmotionPickerTheme {
    val outline = 0xFF292929.toInt()
    val panel = 0xFFBEBEBE.toInt()
    val panelHighlight = 0xFFE7E7E7.toInt()
    val panelShadow = 0xFF5C5C5C.toInt()
    val list = 0xFF969696.toInt()
    val listDarkEdge = 0xFF565656.toInt()
    val listLightEdge = 0xFFCFCFCF.toInt()
    val button = 0xFFD6D6D6.toInt()
    val buttonHovered = 0xFFE4E4E4.toInt()
    val buttonSelected = 0xFFF1D58A.toInt()
    val buttonSelectedHovered = 0xFFF8E4AD.toInt()
    val emptySlot = 0xFFAFAFAF.toInt()
    val emptySlotHovered = 0xFFB8B8B8.toInt()
    val buttonOutline = 0xFF303030.toInt()
    val buttonHighlight = 0xFFF2F2F2.toInt()
    val buttonShadow = 0xFF686868.toInt()
    val selectedOutline = 0xFF8F6F24.toInt()
    val text = 0xFF353535.toInt()
    val tabText = 0xFF4A4A4A.toInt()
    val labelText = 0xFF454545.toInt()
    val mutedText = 0xFF5E5E5E.toInt()
    val secondaryTextOnPanel = 0xFF4A4A4A.toInt()
    val secondaryTextOnList = 0xFF292929.toInt()
    val favorite = 0xFFD19A00.toInt()
    val sliderTrack = 0xFF777777.toInt()
    val sliderFill = 0xFFD19A00.toInt()
    val sliderThumb = 0xFFF1D58A.toInt()
    val error = 0xFF7A302D.toInt()
    val errorOnList = 0xFF541817.toInt()
    val notice = 0xFFC7C7C7.toInt()
    val noticeHighlight = 0xFFDEDEDE.toInt()
    val noticeShadow = 0xFF858585.toInt()
    val noticeOutline = 0xFF555555.toInt()
    val hintBackground = 0xE6CFCFCF.toInt()
    val hintBorder = 0x99656565.toInt()
    val hintCloseHovered = 0xD9E2E2E2.toInt()
    val searchField = 0xFFC4C4C4.toInt()
    val searchFieldFocused = 0xFFCCCCCC.toInt()
    val searchFieldShadow = 0xFF858585.toInt()
    val searchFieldHighlight = 0xFFE0E0E0.toInt()
    val scrollbarBorder = 0xFF595959.toInt()
    val scrollbarTrack = 0xFF777777.toInt()
    val scrollbarThumb = 0xFFBEBEBE.toInt()
    val edgeFade = 0xFF292929.toInt()
    val unavailableCard = 0xFF777777.toInt()
    val unavailableCardHighlight = 0xFF919191.toInt()
    val unavailableCardShadow = 0xFF484848.toInt()
    val unavailableText = 0xFFBEBEBE.toInt()

    fun renderPanel(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        renderRaisedBox(guiGraphics, x, y, width, height, panel, panelHighlight, panelShadow, outline, 2)
    }

    fun renderNotice(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        opacity: Int,
    ) {
        if (opacity <= 0) {
            return
        }
        renderRaisedBox(
            guiGraphics,
            x,
            y,
            width,
            height,
            colorWithOpacity(notice, opacity),
            colorWithOpacity(noticeHighlight, opacity),
            colorWithOpacity(noticeShadow, opacity),
            colorWithOpacity(noticeOutline, opacity),
            1,
        )
    }

    fun colorWithOpacity(color: Int, opacity: Int): Int {
        require(opacity in 0..255) { "Opacity is outside the byte range: $opacity" }
        val sourceAlpha = color ushr 24
        val resultAlpha = (sourceAlpha * opacity + 127) / 255
        return resultAlpha shl 24 or (color and 0x00FFFFFF)
    }

    fun blendColor(start: Int, end: Int, progress: Double): Int {
        require(progress.isFinite()) { "Color blend progress must be finite: $progress" }
        val weight = (progress.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        return blendChannel(start, end, weight, 24) shl 24 or
            (blendChannel(start, end, weight, 16) shl 16) or
            (blendChannel(start, end, weight, 8) shl 8) or
            blendChannel(start, end, weight, 0)
    }

    fun renderButton(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fill: Int,
        border: Int = buttonOutline,
        pressed: Boolean = false,
    ) {
        if (pressed) {
            renderRaisedBox(guiGraphics, x, y, width, height, fill, buttonShadow, buttonHighlight, border, 1)
        } else {
            renderRaisedBox(guiGraphics, x, y, width, height, fill, buttonHighlight, buttonShadow, border, 1)
        }
    }

    fun renderEmptySlot(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        hoverEmphasis: Double,
        targetEmphasis: Double,
    ) {
        val baseFill = blendColor(emptySlot, emptySlotHovered, hoverEmphasis)
        renderRaisedBox(
            guiGraphics,
            x,
            y,
            width,
            height,
            blendColor(baseFill, buttonSelected, targetEmphasis),
            buttonShadow,
            buttonHighlight,
            blendColor(buttonOutline, selectedOutline, targetEmphasis),
            1,
        )
    }

    fun renderList(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        renderRaisedBox(guiGraphics, x, y, width, height, list, listDarkEdge, listLightEdge, outline, 1)
    }

    fun renderHint(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int, opacity: Int) {
        if (opacity <= 0) {
            return
        }
        renderRoundedFill(
            guiGraphics,
            x,
            y,
            width,
            height,
            colorWithOpacity(hintBorder, opacity),
            2,
        )
        renderRoundedFill(
            guiGraphics,
            x + 1,
            y + 1,
            width - 2,
            height - 2,
            colorWithOpacity(hintBackground, opacity),
            1,
        )
    }

    fun renderHintClose(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int, hoverEmphasis: Double, opacity: Int) {
        if (opacity <= 0 || size < 7) {
            return
        }
        val hoverOpacity = (opacity * hoverEmphasis.coerceIn(0.0, 1.0) + 0.5).toInt()
        if (hoverOpacity > 0) {
            renderRoundedFill(
                guiGraphics,
                x,
                y,
                size,
                size,
                colorWithOpacity(hintCloseHovered, hoverOpacity),
                2,
            )
        }
        val color = colorWithOpacity(blendColor(mutedText, tabText, hoverEmphasis), opacity)
        val glyphSize = 5
        val inset = (size - glyphSize) / 2
        repeat(glyphSize) { offset ->
            guiGraphics.fill(x + inset + offset, y + inset + offset, x + inset + offset + 1, y + inset + offset + 1, color)
            guiGraphics.fill(
                x + size - inset - offset - 1,
                y + inset + offset,
                x + size - inset - offset,
                y + inset + offset + 1,
                color,
            )
        }
    }

    fun renderUnavailableCard(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        renderRaisedBox(
            guiGraphics,
            x,
            y,
            width,
            height,
            unavailableCard,
            unavailableCardHighlight,
            unavailableCardShadow,
            listDarkEdge,
            1,
        )
    }

    fun renderUnavailablePreview(guiGraphics: GuiGraphics, x: Int, y: Int, size: Int) {
        require(size >= 8) { "Unavailable preview size is too small: $size" }
        guiGraphics.fill(x, y, x + size, y + size, unavailableCardShadow)
        guiGraphics.fill(x + 1, y + 1, x + size - 1, y + size - 1, list)
        repeat(size - 4) { offset ->
            guiGraphics.fill(x + 2 + offset, y + 2 + offset, x + 3 + offset, y + 3 + offset, errorOnList)
            guiGraphics.fill(x + size - 3 - offset, y + 2 + offset, x + size - 2 - offset, y + 3 + offset, errorOnList)
        }
    }

    fun renderSearchField(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        focused: Boolean,
    ) {
        val border = if (focused) selectedOutline else listDarkEdge
        val fill = if (focused) searchFieldFocused else searchField
        renderRoundedFill(guiGraphics, x, y, width, height, border, 1)
        renderRoundedFill(guiGraphics, x + 1, y + 1, width - 2, height - 2, fill, 0)
        guiGraphics.fill(x + 2, y + 2, x + width - 2, y + 3, searchFieldShadow)
        guiGraphics.fill(x + 2, y + 2, x + 3, y + height - 2, searchFieldShadow)
        guiGraphics.fill(x + 2, y + height - 3, x + width - 2, y + height - 2, searchFieldHighlight)
        guiGraphics.fill(x + width - 3, y + 2, x + width - 2, y + height - 2, searchFieldHighlight)
    }

    fun restoreListGutterFrame(
        guiGraphics: GuiGraphics,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        guiGraphics.fill(left, top, right, top + 1, outline)
        guiGraphics.fill(left, top + 1, right - 1, top + 2, listDarkEdge)
        guiGraphics.fill(left, bottom - 2, right - 1, bottom - 1, listLightEdge)
        guiGraphics.fill(left, bottom - 1, right, bottom, outline)
        guiGraphics.fill(right - 2, top + 2, right - 1, bottom - 2, listLightEdge)
    }

    fun renderScrollbarTrack(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        guiGraphics.fill(x, y, x + width, y + height, scrollbarTrack)
    }

    fun renderScrollbarThumb(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        guiGraphics.fill(x, y, x + width, y + height, scrollbarBorder)
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, scrollbarThumb)
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + 2, panelHighlight)
    }

    private fun renderRaisedBox(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fill: Int,
        highlight: Int,
        shadow: Int,
        border: Int,
        radius: Int,
    ) {
        renderRoundedFill(guiGraphics, x, y, width, height, border, radius)
        renderRoundedFill(guiGraphics, x + 1, y + 1, width - 2, height - 2, fill, (radius - 1).coerceAtLeast(0))
        if (width < 5 || height < 5) {
            return
        }
        guiGraphics.fill(x + radius + 1, y + 1, x + width - radius - 1, y + 2, highlight)
        guiGraphics.fill(x + 1, y + radius + 1, x + 2, y + height - radius - 1, highlight)
        guiGraphics.fill(x + radius + 1, y + height - 2, x + width - radius - 1, y + height - 1, shadow)
        guiGraphics.fill(x + width - 2, y + radius + 1, x + width - 1, y + height - radius - 1, shadow)
    }

    private fun blendChannel(start: Int, end: Int, weight: Int, shift: Int): Int {
        val startChannel = start ushr shift and 0xFF
        val endChannel = end ushr shift and 0xFF
        return (startChannel * (255 - weight) + endChannel * weight + 127) / 255
    }

    private fun renderRoundedFill(
        guiGraphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
        radius: Int,
    ) {
        if (width <= 0 || height <= 0) {
            return
        }
        if (radius <= 0 || width <= radius * 2 || height <= radius * 2) {
            guiGraphics.fill(x, y, x + width, y + height, color)
            return
        }
        guiGraphics.fill(x + radius, y, x + width - radius, y + height, color)
        guiGraphics.fill(x, y + radius, x + width, y + height - radius, color)
        if (radius > 1) {
            guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color)
        }
    }
}

