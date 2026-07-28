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
    val buttonOutline = 0xFF303030.toInt()
    val buttonHighlight = 0xFFF2F2F2.toInt()
    val buttonShadow = 0xFF686868.toInt()
    val selectedOutline = 0xFF8F6F24.toInt()
    val text = 0xFF353535.toInt()
    val tabText = 0xFF4A4A4A.toInt()
    val labelText = 0xFF454545.toInt()
    val mutedText = 0xFF5E5E5E.toInt()
    val favorite = 0xFFD19A00.toInt()
    val error = 0xFFA63B36.toInt()
    val searchField = 0xFFC4C4C4.toInt()
    val searchFieldFocused = 0xFFCCCCCC.toInt()
    val searchFieldShadow = 0xFF858585.toInt()
    val searchFieldHighlight = 0xFFE0E0E0.toInt()
    val scrollbarBorder = 0xFF595959.toInt()
    val scrollbarTrack = 0xFF777777.toInt()
    val scrollbarThumb = 0xFFBEBEBE.toInt()
    val edgeFade = 0xFF292929.toInt()

    fun renderPanel(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        renderRaisedBox(guiGraphics, x, y, width, height, panel, panelHighlight, panelShadow, outline, 2)
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

    fun renderList(guiGraphics: GuiGraphics, x: Int, y: Int, width: Int, height: Int) {
        renderRaisedBox(guiGraphics, x, y, width, height, list, listDarkEdge, listLightEdge, outline, 1)
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
