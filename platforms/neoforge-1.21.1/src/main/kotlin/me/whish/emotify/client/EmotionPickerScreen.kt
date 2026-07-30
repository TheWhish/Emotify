package me.whish.emotify.client

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class EmotionPickerScreen(
    initialContext: EmotionPickerContext,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) : Screen(
    Component.translatable("screen.emotify.emotion_picker"),
) {
    private var context = initialContext
    private val favorites = FavoriteEmotionStore.from(EmotifyClientConfig.loadFavorites())
    private var model = createModel(initialContext)
    private var state = requireNotNull(model.initialState()) { "Emotion picker requires at least one section" }
    private var displayedEmotions = model.emotions(state)
    private var notice: EmotionPickerNotice? = null
    private lateinit var geometry: EmotionPickerGeometry
    private lateinit var grid: EmotionGridList
    private lateinit var searchBox: EmotionSearchBox

    override fun init() {
        val client = requireNotNull(minecraft) { "Minecraft client is unavailable" }
        geometry = EmotionPickerGeometry.calculate(width, height, model.sections)
        model.sections.forEachIndexed { index, section ->
            val bounds = geometry.tabBounds[index]
            addRenderableWidget(
                EmotionTabButton(
                    bounds.x,
                    geometry.tabY,
                    bounds.width,
                    EmotionPickerLayoutMetrics.TAB_HEIGHT,
                    Component.translatable(section.translationKey),
                    section.tabIcon(),
                    { state.sectionId == section.id },
                    { selectSection(section.id) },
                ),
            )
        }
        searchBox = EmotionSearchBox(
            font,
            geometry.searchFieldX,
            geometry.searchFieldY,
            geometry.searchFieldWidth,
            geometry.searchFieldHeight,
            Component.translatable("category.emotify.search"),
        ).also { field ->
            field.setMaxLength(MAXIMUM_SEARCH_LENGTH)
            field.setHint(Component.translatable("screen.emotify.search_hint"))
            field.setValue(state.query)
            field.setResponder(::onSearchChanged)
        }
        addRenderableWidget(searchBox)
        val searching = isSearching()
        grid = EmotionGridList(
            client,
            geometry.listWidth,
            geometry.listHeight(searching),
            geometry.listY(searching),
            geometry.rowWidth,
            geometry.cellWidths,
            ::selectEmotion,
            { presentation -> favorites.isFavorite(presentation.emotionId) },
            ::toggleFavorite,
        )
        grid.setX(geometry.listX)
        grid.replaceEmotions(displayedEmotions)
        addRenderableWidget(grid)
        configureSearchMode()
    }

    override fun repositionElements() {
        val widgetState = captureWidgetState()
        super.repositionElements()
        restoreWidgetState(widgetState)
    }

    override fun tick() {
        val player = minecraft?.player
        val currentContext = ClientHandshakeController.pickerContext()
        if (
            player == null ||
            !player.isAlive ||
            currentContext == null ||
            currentContext.connectionId != context.connectionId
        ) {
            onClose()
            return
        }
        if (currentContext.allowedEmotions != context.allowedEmotions) {
            applyPolicy(currentContext)
        }
        val nowNanos = timeSource.nowNanos()
        notice = notice?.takeUnless { current -> current.isFinished(nowNanos) }
    }

    override fun renderBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        EmotionPickerTheme.renderPanel(
            guiGraphics,
            geometry.panelX,
            geometry.panelY,
            geometry.panelWidth,
            geometry.panelHeight,
        )
        guiGraphics.drawString(
            font,
            title,
            geometry.panelX + (geometry.panelWidth - font.width(title)) / 2,
            geometry.centeredTitleY(font.lineHeight),
            EmotionPickerTheme.tabText,
            false,
        )
        if (isSearching()) {
            EmotionPickerTheme.renderSearchField(
                guiGraphics,
                geometry.searchFieldX,
                geometry.searchFieldY,
                geometry.searchFieldWidth,
                geometry.searchFieldHeight,
                searchBox.isFocused,
            )
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        if (displayedEmotions.isEmpty()) {
            val emptyMessage = when (model.section(state).kind) {
                EmotionPickerSectionKind.FAVORITES -> NO_FAVORITES_MESSAGE
                EmotionPickerSectionKind.SEARCH -> NO_SEARCH_RESULTS_MESSAGE
                EmotionPickerSectionKind.GROUP -> NO_EMOTIONS_MESSAGE
            }
            val searching = isSearching()
            val listY = geometry.listY(searching)
            val listHeight = geometry.listHeight(searching)
            guiGraphics.drawString(
                font,
                emptyMessage,
                geometry.panelX + (geometry.panelWidth - font.width(emptyMessage)) / 2,
                listY + (listHeight - font.lineHeight) / 2,
                EmotionPickerTheme.mutedText,
                false,
            )
        }
        notice?.let { current -> renderNotice(guiGraphics, current, timeSource.nowNanos()) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val enteringSearch = isSearching() && searchBox.isMouseOver(mouseX, mouseY)
        when (
            EmotionPickerMouseRouting.click(
                EmotionPickerController.matchesPickerMouse(button),
                allowsMovementInput(),
                EmotionPickerMovement.isMovementMouse(minecraft, button),
                enteringSearch,
            )
        ) {
            EmotionPickerMouseDecision.CLOSE -> {
                onClose()
                return true
            }
            EmotionPickerMouseDecision.CONSUME_MOVEMENT -> return true
            EmotionPickerMouseDecision.DISPATCH -> Unit
        }
        val shouldClearSearchFocus =
            isSearching() && searchBox.isFocused && !searchBox.isMouseOver(mouseX, mouseY)
        val searchWasFocused = searchBox.isFocused
        val handled = super.mouseClicked(mouseX, mouseY, button)
        if (!searchWasFocused && searchBox.isFocused) {
            minecraft?.let { client -> EmotionPickerMovement.release(client) }
        }
        if (shouldClearSearchFocus && searchBox.isFocused) {
            setFocused(null)
            return true
        }
        return handled
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (
            EmotionPickerMouseRouting.consumeRelease(
                allowsMovementInput(),
                EmotionPickerMovement.isMovementMouse(minecraft, button),
            )
        ) {
            return true
        }
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (EmotionPickerController.matchesPickerKey(keyCode, scanCode)) {
            val textInputFocused = isSearchInputFocused()
            if (EmotionPickerController.shouldClosePicker(keyCode, scanCode, textInputFocused)) {
                EmotionPickerMovement.keyPressed(minecraft, keyCode, scanCode)
                onClose()
            }
            if (!textInputFocused) {
                return true
            }
        }
        if (allowsMovementInput() && EmotionPickerMovement.keyPressed(minecraft, keyCode, scanCode)) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val movementReleased = EmotionPickerMovement.keyReleased(minecraft, keyCode, scanCode)
        if (allowsMovementInput() && movementReleased) {
            return true
        }
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        minecraft?.let { client ->
            EmotionPickerMovement.release(
                client,
                restorePhysicalState = client.level != null && client.player?.isAlive == true && client.overlay == null,
            )
        }
        super.removed()
    }

    internal fun allowsMovementInput(): Boolean {
        if (!isSearching()) {
            return true
        }
        return !isSearchInputFocused()
    }

    internal fun refreshResources() {
        applyPolicy(context)
    }

    private fun selectSection(sectionId: String) {
        val nextState = model.selectSection(state, sectionId)
        if (nextState == state) {
            return
        }
        state = nextState
        displayedEmotions = model.emotions(state)
        grid.replaceEmotions(displayedEmotions)
        configureSearchMode()
    }

    private fun onSearchChanged(query: String) {
        val nextState = model.updateQuery(state, query)
        if (nextState == state) {
            return
        }
        state = nextState
        displayedEmotions = model.emotions(state)
        grid.replaceEmotions(displayedEmotions)
    }

    private fun selectEmotion(presentation: EmotionPresentation) {
        val listener = minecraft?.connection
        val result = if (listener == null) {
            ClientSelectionSendResult.NOT_CONNECTED
        } else {
            ClientHandshakeController.sendSelection(listener, presentation.emotionId)
        }
        if (result == ClientSelectionSendResult.SENT) {
            return
        }
        showNotice(Component.translatable(checkNotNull(result.messageTranslationKey())))
    }

    internal fun showNotice(message: Component) {
        notice = EmotionPickerNotice.show(notice, message.string, timeSource.nowNanos())
    }

    internal fun selectionAccepted() {
        onClose()
    }

    private fun applyPolicy(updatedContext: EmotionPickerContext) {
        val updatedModel = createModel(updatedContext)
        val initialState = updatedModel.initialState()
        if (initialState == null) {
            onClose()
            return
        }
        val retainedSection = updatedModel.sections.firstOrNull { section -> section.id == state.sectionId }
        val widgetState = if (retainedSection == null) null else captureWidgetState()
        context = updatedContext
        model = updatedModel
        state = if (retainedSection == null) {
            initialState.copy(query = state.query)
        } else {
            EmotionPickerState(retainedSection.id, 0, state.query)
        }
        displayedEmotions = model.emotions(state)
        notice = null
        rebuildWidgets()
        restoreWidgetState(widgetState)
    }

    private fun toggleFavorite(presentation: EmotionPresentation) {
        if (favorites.toggle(presentation.emotionId) == FavoriteToggleResult.UNKNOWN_EMOTION) {
            return
        }
        val orderedIds = favorites.orderedIds()
        model = model.withFavorites(favorites.snapshot)
        state = EmotionPickerState(state.sectionId, state.firstVisibleRow, state.query)
        displayedEmotions = model.emotions(state)
        if (model.section(state).kind == EmotionPickerSectionKind.FAVORITES) {
            grid.replaceEmotions(displayedEmotions, resetScroll = false)
        }
        EmotifyClientConfig.saveFavorites(orderedIds)
    }

    private fun configureSearchMode() {
        val searching = isSearching()
        searchBox.visible = searching
        searchBox.active = searching
        grid.setViewport(
            geometry.listX,
            geometry.listY(searching),
            geometry.listWidth,
            geometry.listHeight(searching),
        )
        if (!searching) {
            if (searchBox.isFocused) {
                setFocused(null)
            }
        }
    }

    private fun isSearching(): Boolean =
        model.section(state).kind == EmotionPickerSectionKind.SEARCH

    private fun isSearchInputFocused(): Boolean =
        isSearching() && ::searchBox.isInitialized && searchBox.isFocused

    private fun renderNotice(guiGraphics: GuiGraphics, current: EmotionPickerNotice, nowNanos: Long) {
        val opacity = current.opacityAt(nowNanos)
        val alpha = EmotionPickerNoticeAnimation.renderAlpha(opacity)
        if (alpha == 0) {
            return
        }
        val maximumTextWidth = EmotionPickerNoticeLayout.maximumTextWidth(width)
        val value = fittedNoticeMessage(current.message, maximumTextWidth)
        val textWidth = font.width(value)
        val bounds = EmotionPickerNoticeLayout.bounds(
            width,
            height,
            geometry.panelX,
            geometry.panelY,
            geometry.panelWidth,
            geometry.panelHeight,
            textWidth,
            font.lineHeight,
        )
        EmotionPickerTheme.renderNotice(guiGraphics, bounds.x, bounds.y, bounds.width, bounds.height, alpha)
        guiGraphics.drawString(
            font,
            value,
            bounds.x + (bounds.width - textWidth) / 2,
            bounds.y + (bounds.height - font.lineHeight) / 2,
            EmotionPickerTheme.colorWithOpacity(EmotionPickerTheme.error, alpha),
            false,
        )
    }

    private fun fittedNoticeMessage(message: String, maximumWidth: Int): String {
        if (font.width(message) <= maximumWidth) {
            return message
        }
        val contentWidth = (maximumWidth - font.width(TRUNCATION_MARK)).coerceAtLeast(0)
        return font.plainSubstrByWidth(message, contentWidth).trimEnd() + TRUNCATION_MARK
    }

    private fun captureWidgetState(): PickerWidgetState? {
        if (!::grid.isInitialized || !::searchBox.isInitialized) {
            return null
        }
        return PickerWidgetState(
            grid.retainedScrollAmount(),
            isSearching() && searchBox.isFocused,
        )
    }

    private fun restoreWidgetState(widgetState: PickerWidgetState?) {
        if (widgetState == null) {
            return
        }
        grid.restoreScrollAmount(widgetState.scrollAmount)
        if (widgetState.searchFocused && isSearching()) {
            setFocused(searchBox)
            minecraft?.let { client -> EmotionPickerMovement.release(client) }
        }
    }

    private fun createModel(pickerContext: EmotionPickerContext): EmotionPickerModel =
        EmotionPickerModel.from(
            pickerContext.allowedEmotions,
            favorites.snapshot,
        ) { presentation ->
            Component.translatable(presentation.translationKey).string
        }

    private fun EmotionPickerSection.tabIcon(): EmotionTabIcon = when (kind) {
        EmotionPickerSectionKind.FAVORITES -> EmotionTabIcon.FAVORITES
        EmotionPickerSectionKind.GROUP -> EmotionTabIcon.NONE
        EmotionPickerSectionKind.SEARCH -> EmotionTabIcon.SEARCH
    }

    companion object {
        private val NO_FAVORITES_MESSAGE = Component.translatable("screen.emotify.no_favorites")
        private val NO_SEARCH_RESULTS_MESSAGE = Component.translatable("screen.emotify.no_search_results")
        private val NO_EMOTIONS_MESSAGE = Component.translatable("message.emotify.no_emotions")
        private const val MAXIMUM_SEARCH_LENGTH = 64
        private const val TRUNCATION_MARK = ".."
    }

    private data class PickerWidgetState(
        val scrollAmount: Double,
        val searchFocused: Boolean,
    )
}

internal fun ClientSelectionSendResult.messageTranslationKey(): String? = when (this) {
    ClientSelectionSendResult.SENT -> null
    ClientSelectionSendResult.NOT_CONNECTED,
    ClientSelectionSendResult.HANDSHAKE_UNAVAILABLE,
    ClientSelectionSendResult.CHANNEL_UNAVAILABLE,
    -> "message.emotify.unavailable"
    ClientSelectionSendResult.EMOTION_UNAVAILABLE -> "message.emotify.selection_unavailable"
    ClientSelectionSendResult.PLAYER_STATE -> "message.emotify.player_state"
    ClientSelectionSendResult.REQUEST_PENDING -> "message.emotify.request_pending"
    ClientSelectionSendResult.REQUEST_THROTTLED -> "message.emotify.request_throttled"
    ClientSelectionSendResult.EMOTION_ACTIVE -> "message.emotify.emotion_active"
}
