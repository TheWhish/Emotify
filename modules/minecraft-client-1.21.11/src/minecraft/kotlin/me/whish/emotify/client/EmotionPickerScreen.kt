package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import me.whish.emotify.client.picker.EmotionPickerBrand
import me.whish.emotify.client.picker.EmotionPickerContext
import me.whish.emotify.client.picker.EmotionPickerDragGesture
import me.whish.emotify.client.picker.EmotionPickerDragPreview
import me.whish.emotify.client.picker.EmotionPickerGeometry
import me.whish.emotify.client.picker.EmotionPickerGridContent
import me.whish.emotify.client.picker.EmotionPickerGridItem
import me.whish.emotify.client.picker.EmotionPickerHintAnimation
import me.whish.emotify.client.picker.EmotionPickerHintBounds
import me.whish.emotify.client.picker.EmotionPickerHintLayout
import me.whish.emotify.client.picker.EmotionPickerHintTextLayout
import me.whish.emotify.client.picker.EmotionPickerHoverAnimation
import me.whish.emotify.client.picker.EmotionPickerKeyboardRouting
import me.whish.emotify.client.picker.EmotionPickerLayoutMetrics
import me.whish.emotify.client.picker.EmotionPickerModel
import me.whish.emotify.client.picker.EmotionPickerMouseDecision
import me.whish.emotify.client.picker.EmotionPickerMouseRouting
import me.whish.emotify.client.picker.EmotionPickerNotice
import me.whish.emotify.client.picker.EmotionPickerNoticeAnimation
import me.whish.emotify.client.picker.EmotionPickerNoticeLayout
import me.whish.emotify.client.picker.EmotionPickerSection
import me.whish.emotify.client.picker.EmotionPickerSectionKind
import me.whish.emotify.client.picker.EmotionPickerState
import me.whish.emotify.client.picker.EmotionPickerViewportMode
import me.whish.emotify.client.picker.messageTranslationKey
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.client.input.QuickSlotInputGate
import me.whish.emotify.client.input.QuickSlotInputRouting
import me.whish.emotify.client.input.QuickSlotKeyResolver
import me.whish.emotify.client.input.QuickSlotPressDecision
import me.whish.emotify.client.settings.ClientConfigurationSnapshot
import me.whish.emotify.client.settings.ClientConfigurationSchema
import me.whish.emotify.client.state.ClientSelectionSendResult
import me.whish.emotify.client.state.FavoriteEmotionStore
import me.whish.emotify.client.state.FavoriteToggleResult
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component

class EmotionPickerScreen(
    initialContext: EmotionPickerContext,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) : Screen(
    Component.literal(EmotionPickerBrand.TITLE),
) {
    private var context = initialContext
    private var favorites = FavoriteEmotionStore.from(EmotifyClientConfig.loadFavorites())
    private var quickSlots = loadQuickSlotsSnapshot()
    private val quickSlotInputGate = QuickSlotInputGate()
    private var model = createModel(initialContext)
    private var state = requireNotNull(model.initialState()) { "Emotion picker requires at least one section" }
    private var displayedEmotions = model.emotions(state)
    private var displayedGridItems = createGridItems(displayedEmotions)
    private var notice: EmotionPickerNotice? = null
    private lateinit var geometry: EmotionPickerGeometry
    private lateinit var grid: EmotionGridList
    private lateinit var searchBox: EmotionSearchBox
    private val quickSlotButtons = ArrayList<ModernEmotionQuickSlotButton>()
    private var dragSession: EmotionDragSession? = null
    private var dragTargetSlot = NO_QUICK_SLOT
    private var reducedMotion = false
    private var customCopyHintDismissed = false
    private var customCopyHintStartedAtNanos = Long.MIN_VALUE
    private var customCopyHintDismissStartedAtNanos = Long.MIN_VALUE
    private val customHintCloseHoverMotion = EmotionPickerHoverAnimation.Motion()
    private val customHintVisual = CustomHintVisualState()
    private var retainedWidgetState: PickerWidgetState? = null

    override fun init() {
        val client = minecraft
        cancelDrag()
        quickSlots = loadQuickSlotsSnapshot()
        reducedMotion = EmotifyClientConfig.settings().reducedMotion
        customCopyHintDismissed = EmotifyClientConfig.isCustomCopyHintDismissed()
        customCopyHintDismissStartedAtNanos = Long.MIN_VALUE
        quickSlotButtons.clear()
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
        addRenderableWidget(
            EmotionPickerSideActionButton(
                geometry.sideActionX,
                geometry.sideActionY(0),
                Component.translatable("screen.emotify.open_emoji_folder"),
                EmotionPickerSideActionIcon.FOLDER,
                ::openEmojiFolder,
            ),
        )
        addRenderableWidget(
            EmotionPickerSideActionButton(
                geometry.sideActionX,
                geometry.sideActionY(1),
                Component.translatable("screen.emotify.settings"),
                EmotionPickerSideActionIcon.SETTINGS,
                ::openSettings,
            ),
        )
        geometry.quickSlotBounds.forEachIndexed { index, bounds ->
            quickSlotButtons += addRenderableWidget(
                ModernEmotionQuickSlotButton(
                    index,
                    bounds,
                    { quickSlots.quickSlot(index) != null },
                    { quickSlotPresentation(index) },
                    { dragTargetSlot == index },
                    timeSource::nowNanos,
                    { reducedMotion },
                    ::selectQuickSlot,
                    ::clearQuickSlot,
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
        val viewportMode = viewportMode()
        grid = EmotionGridList(
            client,
            geometry.listWidth,
            geometry.listHeight(viewportMode),
            geometry.listY(viewportMode),
            geometry.rowWidth,
            geometry.cellWidths,
            ::selectEmotion,
            { presentation -> favorites.isFavorite(presentation.emotionId) },
            ::toggleFavorite,
            ::beginEmotionPointerPress,
            ::isDragging,
        )
        grid.setX(geometry.listX)
        grid.replaceItems(displayedGridItems)
        addRenderableWidget(grid)
        configureSearchMode()
        retainedWidgetState?.let(::restoreWidgetState)
        retainedWidgetState = null
    }

    override fun repositionElements() {
        val widgetState = captureWidgetState()
        super.repositionElements()
        restoreWidgetState(widgetState)
    }

    override fun tick() {
        val client = minecraft
        val player = client.player
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
        synchronizeQuickSlotInput(client)
        if (currentContext.allowedEmotions != context.allowedEmotions) {
            applyPolicy(currentContext)
        }
        CustomEmojiRegistry.refreshIfChanged(client) {
            if (client.screen === this) {
                favorites = FavoriteEmotionStore.from(EmotifyClientConfig.loadFavorites())
                quickSlots = loadQuickSlotsSnapshot()
                applyPolicy(context)
            }
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
        if (displayedGridItems.isEmpty()) {
            val emptyMessage = when (model.section(state).kind) {
                EmotionPickerSectionKind.FAVORITES -> NO_FAVORITES_MESSAGE
                EmotionPickerSectionKind.SEARCH -> NO_SEARCH_RESULTS_MESSAGE
                EmotionPickerSectionKind.GROUP -> if (state.sectionId == EmotionPickerModel.CUSTOM_SECTION_ID) {
                    NO_CUSTOM_EMOJIS_MESSAGE
                } else {
                    NO_EMOTIONS_MESSAGE
                }
            }
            val viewportMode = viewportMode()
            val listY = geometry.listY(viewportMode)
            val listHeight = geometry.listHeight(viewportMode)
            guiGraphics.drawString(
                font,
                emptyMessage,
                geometry.panelX + (geometry.panelWidth - font.width(emptyMessage)) / 2,
                listY + (listHeight - font.lineHeight) / 2,
                EmotionPickerTheme.mutedText,
                false,
            )
        }
        val nowNanos = timeSource.nowNanos()
        renderCustomHint(guiGraphics, mouseX, mouseY, nowNanos)
        notice?.let { current -> renderNotice(guiGraphics, current, nowNanos) }
        renderDragPreview(guiGraphics, mouseX, mouseY, nowNanos)
    }

    override fun isPauseScreen(): Boolean = false

    override fun added() {
        super.added()
        val client = minecraft
        EmotionPickerMovement.begin(client)
        if (allowsMovementInput()) {
            EmotionPickerMovement.update(client)
        } else {
            EmotionPickerMovement.release(client)
        }
        CustomEmojiRegistry.refreshIfChanged(client) {
            if (client.screen === this) {
                favorites = FavoriteEmotionStore.from(EmotifyClientConfig.loadFavorites())
                quickSlots = loadQuickSlotsSnapshot()
                applyPolicy(context)
            }
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val enteringSearch = isSearching() && searchBox.isMouseOver(event.x(), event.y())
        when (
            EmotionPickerMouseRouting.click(
                EmotionPickerController.matchesPickerMouse(event),
                allowsMovementInput(),
                EmotionPickerMovement.isMovementMouse(minecraft, event),
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
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && dismissCustomHintAt(event.x(), event.y())) {
            return true
        }
        val shouldClearSearchFocus =
            isSearching() && searchBox.isFocused && !searchBox.isMouseOver(event.x(), event.y())
        val searchWasFocused = searchBox.isFocused
        val handled = super.mouseClicked(event, doubleClick)
        if (!searchWasFocused && searchBox.isFocused) {
            EmotionPickerMovement.release(minecraft)
        }
        if (shouldClearSearchFocus && searchBox.isFocused) {
            setFocused(null)
            return true
        }
        return handled
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && finishEmotionPointerPress(event.x(), event.y())) {
            return true
        }
        if (
            EmotionPickerMouseRouting.consumeRelease(
                allowsMovementInput(),
                EmotionPickerMovement.isMovementMouse(minecraft, event),
            )
        ) {
            return true
        }
        return super.mouseReleased(event)
    }

    override fun mouseDragged(
        event: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val current = dragSession
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && current != null) {
            current.pointerX = event.x()
            current.pointerY = event.y()
            if (!current.dragging && EmotionPickerDragGesture.shouldStart(
                    current.originX,
                    current.originY,
                    event.x(),
                    event.y(),
                )
            ) {
                current.startDragging(timeSource.nowNanos())
            }
            if (current.dragging) {
                dragTargetSlot = geometry.quickSlotAt(event.x(), event.y())
            }
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val movementPressed = EmotionPickerMovement.keyPressed(minecraft, event)
        if (EmotionPickerController.matchesPickerKey(event)) {
            val textInputFocused = isSearchInputFocused()
            if (EmotionPickerController.shouldClosePicker(event, textInputFocused)) {
                onClose()
            }
            if (!textInputFocused) {
                return true
            }
        }
        val quickSlotInputIndex = QuickSlotKeyResolver.resolve(
            event.key(),
            InputConstants.KEY_1,
            InputConstants.KEY_NUMPAD1,
        )
        if (quickSlotInputIndex != QuickSlotKeyResolver.NO_SLOT) {
            val edgePress = quickSlotInputGate.press(quickSlotInputIndex)
            val quickSlotIndex = QuickSlotKeyResolver.slotIndex(quickSlotInputIndex)
            when (QuickSlotInputRouting.press(isSearchInputFocused(), edgePress)) {
                QuickSlotPressDecision.ACTIVATE_WITH_CLICK_FEEDBACK -> activateQuickSlotFromKeyboard(quickSlotIndex)
                QuickSlotPressDecision.CONSUME_REPEAT -> Unit
                QuickSlotPressDecision.DISPATCH_TO_TEXT_INPUT ->
                    return super.keyPressed(event)
            }
            return true
        }
        if (EmotionPickerKeyboardRouting.consumePress(allowsMovementInput(), movementPressed)) {
            return true
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        val movementReleased = EmotionPickerMovement.keyReleased(minecraft, event)
        val quickSlotInputIndex = QuickSlotKeyResolver.resolve(
            event.key(),
            InputConstants.KEY_1,
            InputConstants.KEY_NUMPAD1,
        )
        if (quickSlotInputIndex != QuickSlotKeyResolver.NO_SLOT) {
            quickSlotInputGate.release(quickSlotInputIndex)
            if (!isSearchInputFocused()) {
                return true
            }
        }
        if (allowsMovementInput() && movementReleased) {
            return true
        }
        return super.keyReleased(event)
    }

    override fun removed() {
        cancelDrag()
        quickSlotInputGate.clear()
        val client = minecraft
        EmotionPickerMovement.release(
            client,
            restorePhysicalState = client.level != null && client.player?.isAlive == true && client.overlay == null,
        )
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
        displayedGridItems = createGridItems(displayedEmotions)
        grid.replaceItems(displayedGridItems)
        configureSearchMode()
    }

    private fun openSettings() {
        retainedWidgetState = captureWidgetState()
        minecraft.setScreen(EmotifySettingsScreen(this))
    }

    private fun openEmojiFolder() {
        val client = minecraft
        CustomEmojiRegistry.openDirectory(client) {
            if (client.screen === this) {
                showNotice(Component.translatable("message.emotify.emoji_folder_failed"))
            }
        }
    }

    private fun onSearchChanged(query: String) {
        val nextState = model.updateQuery(state, query)
        if (nextState == state) {
            return
        }
        state = nextState
        displayedEmotions = model.emotions(state)
        displayedGridItems = createGridItems(displayedEmotions)
        grid.replaceItems(displayedGridItems)
    }

    private fun selectEmotion(presentation: EmotionPresentation) {
        val listener = minecraft.connection
        val result = if (listener == null) {
            ClientSelectionSendResult.NOT_CONNECTED
        } else {
            if (CustomEmojiRegistry.contains(presentation.emotionId)) {
                ClientHandshakeController.sendCustomSelection(presentation.emotionId)
            } else {
                ClientHandshakeController.sendSelection(listener, presentation.emotionId)
            }
        }
        if (result == ClientSelectionSendResult.SENT) {
            return
        }
        showNotice(Component.translatable(checkNotNull(result.messageTranslationKey())))
    }

    private fun selectQuickSlot(slotIndex: Int) {
        val emotionId = quickSlots.quickSlot(slotIndex)
        if (emotionId == null) {
            showNotice(Component.translatable("message.emotify.quick_slot_empty", slotIndex + 1))
            return
        }
        val presentation = EmotionPresentationCatalog.find(emotionId) ?: CustomEmojiRegistry.find(emotionId)
        if (presentation == null) {
            showNotice(Component.translatable("message.emotify.quick_slot_unavailable", slotIndex + 1))
            return
        }
        selectEmotion(presentation)
    }

    private fun activateQuickSlotFromKeyboard(slotIndex: Int) {
        val button = quickSlotButtons.getOrNull(slotIndex)
        if (button == null) {
            selectQuickSlot(slotIndex)
            return
        }
        button.activateFromKeyboard(minecraft.soundManager)
    }

    private fun clearQuickSlot(slotIndex: Int) {
        val updated = quickSlots.clearQuickSlot(slotIndex)
        if (updated === quickSlots) {
            return
        }
        quickSlots = updated
        EmotifyClientConfig.saveQuickSlots(updated.quickSlots)
    }

    private fun assignQuickSlot(slotIndex: Int, presentation: EmotionPresentation) {
        val updated = quickSlots.assignQuickSlot(slotIndex, presentation.emotionId)
        if (updated !== quickSlots) {
            quickSlots = updated
            EmotifyClientConfig.saveQuickSlots(updated.quickSlots)
        }
        quickSlotButtons.getOrNull(slotIndex)?.startLanding(timeSource.nowNanos())
    }

    internal fun showNotice(message: Component) {
        notice = EmotionPickerNotice.show(notice, message.string, timeSource.nowNanos())
    }

    internal fun selectionAccepted() {
        onClose()
    }

    private fun applyPolicy(updatedContext: EmotionPickerContext) {
        cancelDrag()
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
        displayedGridItems = createGridItems(displayedEmotions)
        notice = null
        rebuildWidgets()
        restoreWidgetState(widgetState)
    }

    private fun toggleFavorite(presentation: EmotionPresentation) {
        val availableIds = model.sections.last().emotions.map(EmotionPresentation::emotionId)
        val result = favorites.toggle(presentation.emotionId, availableIds)
        when (result) {
            FavoriteToggleResult.ADDED,
            FavoriteToggleResult.REMOVED,
            -> Unit
            FavoriteToggleResult.CAPACITY_REACHED -> {
                showNotice(Component.translatable("message.emotify.favorite_capacity", EmotionCatalog.MAX_SIZE))
                return
            }
            FavoriteToggleResult.UNKNOWN_EMOTION -> return
        }
        val orderedIds = favorites.orderedIds()
        model = model.withFavorites(favorites.snapshot)
        state = EmotionPickerState(state.sectionId, state.firstVisibleRow, state.query)
        displayedEmotions = model.emotions(state)
        displayedGridItems = createGridItems(displayedEmotions)
        if (model.section(state).kind == EmotionPickerSectionKind.FAVORITES) {
            grid.replaceItems(displayedGridItems, resetScroll = false)
        }
        EmotifyClientConfig.saveFavorites(orderedIds)
    }

    private fun configureSearchMode() {
        val searching = isSearching()
        searchBox.visible = searching
        searchBox.active = searching
        grid.setViewport(
            geometry.listX,
            geometry.listY(viewportMode()),
            geometry.listWidth,
            geometry.listHeight(viewportMode()),
        )
        if (!searching) {
            if (searchBox.isFocused) {
                setFocused(null)
            }
        }
    }

    private fun isSearching(): Boolean =
        model.section(state).kind == EmotionPickerSectionKind.SEARCH

    private fun isCustomSection(): Boolean = state.sectionId == EmotionPickerModel.CUSTOM_SECTION_ID

    private fun viewportMode(): EmotionPickerViewportMode =
        if (isSearching()) EmotionPickerViewportMode.SEARCH else EmotionPickerViewportMode.NORMAL

    private fun createGridItems(emotions: List<EmotionPresentation>): List<EmotionPickerGridItem> =
        if (isCustomSection()) {
            EmotionPickerGridContent.custom(emotions, CustomEmojiRegistry.diagnostics())
        } else {
            EmotionPickerGridContent.regular(emotions)
        }

    private fun isSearchInputFocused(): Boolean =
        isSearching() && ::searchBox.isInitialized && searchBox.isFocused

    private fun beginEmotionPointerPress(
        presentation: EmotionPresentation,
        mouseX: Double,
        mouseY: Double,
        sourceX: Double,
        sourceY: Double,
    ) {
        val nowNanos = timeSource.nowNanos()
        dragSession = EmotionDragSession(
            presentation,
            mouseX,
            mouseY,
            mouseX,
            mouseY,
            EmotionPickerDragPreview.Motion(sourceX, sourceY),
            nowNanos,
        )
        dragTargetSlot = NO_QUICK_SLOT
    }

    private fun finishEmotionPointerPress(mouseX: Double, mouseY: Double): Boolean {
        val current = dragSession ?: return false
        dragSession = null
        dragTargetSlot = NO_QUICK_SLOT
        if (current.dragging) {
            val slotIndex = geometry.quickSlotAt(mouseX, mouseY)
            if (slotIndex != NO_QUICK_SLOT) {
                assignQuickSlot(slotIndex, current.presentation)
            }
        } else {
            selectEmotion(current.presentation)
        }
        return true
    }

    private fun renderDragPreview(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        nowNanos: Long,
    ) {
        val current = dragSession ?: return
        current.pointerX = mouseX.toDouble()
        current.pointerY = mouseY.toDouble()
        if (!current.dragging) {
            return
        }
        dragTargetSlot = geometry.quickSlotAt(current.pointerX, current.pointerY)
        if (reducedMotion) {
            current.motion.x = current.pointerX
            current.motion.y = current.pointerY
            current.motion.velocityX = 0.0
            current.motion.velocityY = 0.0
        } else {
            val elapsedSeconds = ((nowNanos - current.lastFrameNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
                .coerceAtMost(MAXIMUM_DRAG_FRAME_SECONDS)
            EmotionPickerDragPreview.advance(
                current.motion,
                current.pointerX,
                current.pointerY,
                elapsedSeconds,
            )
        }
        current.lastFrameNanos = nowNanos
        val previewSize = geometry.quickSlotHeight
        val iconSize = geometry.quickSlotBounds.first().previewSize
        val dragElapsedNanos = (nowNanos - current.dragStartedNanos).coerceAtLeast(0L)
        val scale = if (reducedMotion) 1.0 else EmotionPickerDragPreview.liftScale(dragElapsedNanos)
        val tiltDegrees = if (reducedMotion) 0.0 else EmotionPickerDragPreview.tiltDegrees(current.motion)
        val pose = guiGraphics.pose()
        pose.pushMatrix()
        try {
            pose.translate(current.motion.x.toFloat(), current.motion.y.toFloat())
            pose.rotate(Math.toRadians(tiltDegrees).toFloat())
            pose.scale(scale.toFloat(), scale.toFloat())
            pose.translate(-previewSize / 2.0F, -previewSize / 2.0F)
            EmotionPickerTheme.renderButton(
                guiGraphics,
                0,
                0,
                previewSize,
                previewSize,
                EmotionPickerTheme.buttonSelectedHovered,
                EmotionPickerTheme.selectedOutline,
                pressed = true,
            )
            val region = current.presentation.regionAt(nowNanos / 1_000_000L)
            guiGraphics.blit(
                RenderPipelines.GUI_TEXTURED,
                EmotionTextureResources.resolve(current.presentation.textureId),
                (previewSize - iconSize) / 2,
                (previewSize - iconSize) / 2,
                region.x.toFloat(),
                region.y.toFloat(),
                iconSize,
                iconSize,
                region.width,
                region.height,
                region.textureWidth,
                region.textureHeight,
            )
        } finally {
            pose.popMatrix()
        }
    }

    private fun synchronizeQuickSlotInput(client: Minecraft) {
        val window = client.window
        var physicallyPressedMask = 0
        repeat(ClientConfigurationSchema.QUICK_SLOT_COUNT) { index ->
            if (InputConstants.isKeyDown(window, InputConstants.KEY_1 + index)) {
                physicallyPressedMask = physicallyPressedMask or (1 shl index)
            }
            if (InputConstants.isKeyDown(window, InputConstants.KEY_NUMPAD1 + index)) {
                physicallyPressedMask = physicallyPressedMask or
                    (1 shl (index + ClientConfigurationSchema.QUICK_SLOT_COUNT))
            }
        }
        quickSlotInputGate.releaseMissing(physicallyPressedMask)
    }

    private fun cancelDrag() {
        dragSession = null
        dragTargetSlot = NO_QUICK_SLOT
    }

    private fun isDragging(presentation: EmotionPresentation): Boolean =
        dragSession?.takeIf(EmotionDragSession::dragging)?.presentation?.emotionId == presentation.emotionId

    private fun quickSlotPresentation(slotIndex: Int): EmotionPresentation? {
        val emotionId = quickSlots.quickSlot(slotIndex) ?: return null
        return EmotionPresentationCatalog.find(emotionId) ?: CustomEmojiRegistry.find(emotionId)
    }

    private fun renderCustomHint(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        nowNanos: Long,
    ) {
        if (!shouldRenderCustomHint(nowNanos)) {
            return
        }
        val text = customHintText()
        val bounds = customHintBounds(text, nowNanos)
        val visual = customHintVisualState(nowNanos)
        val panelAlpha = EmotionPickerNoticeAnimation.renderAlpha(visual.panelOpacity)
        if (panelAlpha == 0) {
            return
        }
        val closeHovered = !customCopyHintDismissed && bounds.containsClose(
            mouseX.toDouble(),
            mouseY.toDouble(),
            visual.horizontalScale,
            visual.verticalScale,
            visual.verticalOffset,
            visual.closeScale,
        )
        val closeHoverEmphasis = customHintCloseHoverMotion.advance(closeHovered, nowNanos)
        val pose = guiGraphics.pose()
        val centerX = bounds.x + bounds.width / 2.0
        val centerY = bounds.y + bounds.height / 2.0
        pose.pushMatrix()
        try {
            pose.translate(centerX.toFloat(), (centerY + visual.verticalOffset).toFloat())
            pose.scale(visual.horizontalScale.toFloat(), visual.verticalScale.toFloat())
            pose.translate(-centerX.toFloat(), -centerY.toFloat())
            EmotionPickerTheme.renderHint(guiGraphics, bounds.x, bounds.y, bounds.width, bounds.height, panelAlpha)
            val closeAlpha = EmotionPickerNoticeAnimation.renderAlpha(visual.closeOpacity)
            if (closeAlpha > 0) {
                val closeCenterX = bounds.closeX + bounds.closeSize / 2.0
                val closeCenterY = bounds.closeY + bounds.closeSize / 2.0
                pose.pushMatrix()
                try {
                    pose.translate(closeCenterX.toFloat(), closeCenterY.toFloat())
                    pose.scale(visual.closeScale.toFloat(), visual.closeScale.toFloat())
                    pose.translate(-closeCenterX.toFloat(), -closeCenterY.toFloat())
                    EmotionPickerTheme.renderHintClose(
                        guiGraphics,
                        bounds.closeX,
                        bounds.closeY,
                        bounds.closeSize,
                        closeHoverEmphasis,
                        closeAlpha,
                    )
                } finally {
                    pose.popMatrix()
                }
            }
            val textAlpha = EmotionPickerNoticeAnimation.renderAlpha(visual.textOpacity)
            if (textAlpha > 0) {
                val textX = EmotionPickerHintTextLayout.x(bounds)
                val textY = EmotionPickerHintTextLayout.y(bounds, font.lineHeight)
                pose.pushMatrix()
                try {
                    pose.translate(
                        (textX + visual.textHorizontalOffset).toFloat(),
                        textY.toFloat(),
                    )
                    guiGraphics.drawString(
                        font,
                        text,
                        0,
                        0,
                        EmotionPickerTheme.colorWithOpacity(EmotionPickerTheme.secondaryTextOnPanel, textAlpha),
                        false,
                    )
                } finally {
                    pose.popMatrix()
                }
            }
        } finally {
            pose.popMatrix()
        }
    }

    private fun customHintVisualState(nowNanos: Long): CustomHintVisualState {
        if (reducedMotion) {
            val opacity = if (customCopyHintDismissed) 0.0 else 1.0
            customHintVisual.panelOpacity = opacity
            customHintVisual.textOpacity = opacity
            customHintVisual.closeOpacity = opacity
            customHintVisual.horizontalScale = 1.0
            customHintVisual.verticalScale = 1.0
            customHintVisual.verticalOffset = 0.0
            customHintVisual.textHorizontalOffset = 0.0
            customHintVisual.closeScale = 1.0
            return customHintVisual
        }
        val ageMillis = customHintAgeMillis(nowNanos)
        val dismissAgeMillis = if (customCopyHintDismissed) customHintDismissAgeMillis(nowNanos) else 0.0
        val dismissOpacity = if (customCopyHintDismissed) {
            EmotionPickerHintAnimation.dismissOpacityAt(dismissAgeMillis)
        } else {
            1.0
        }
        val dismissScale = if (customCopyHintDismissed) {
            EmotionPickerHintAnimation.dismissScaleAt(dismissAgeMillis)
        } else {
            1.0
        }
        val dismissOffset = if (customCopyHintDismissed) {
            EmotionPickerHintAnimation.dismissVerticalOffsetAt(dismissAgeMillis)
        } else {
            0.0
        }
        customHintVisual.panelOpacity = EmotionPickerHintAnimation.panelOpacityAt(ageMillis) * dismissOpacity
        customHintVisual.textOpacity = EmotionPickerHintAnimation.textOpacityAt(ageMillis) * dismissOpacity
        customHintVisual.closeOpacity = EmotionPickerHintAnimation.closeOpacityAt(ageMillis) * dismissOpacity
        customHintVisual.horizontalScale = EmotionPickerHintAnimation.horizontalScaleAt(ageMillis) * dismissScale
        customHintVisual.verticalScale = dismissScale
        customHintVisual.verticalOffset = EmotionPickerHintAnimation.verticalOffsetAt(ageMillis) + dismissOffset
        customHintVisual.textHorizontalOffset = EmotionPickerHintAnimation.textHorizontalOffsetAt(ageMillis)
        customHintVisual.closeScale = EmotionPickerHintAnimation.closeScaleAt(ageMillis)
        return customHintVisual
    }

    private fun dismissCustomHintAt(mouseX: Double, mouseY: Double): Boolean {
        if (!shouldShowCustomHint()) {
            return false
        }
        val nowNanos = timeSource.nowNanos()
        val bounds = customHintBounds(customHintText(), nowNanos)
        val visual = customHintVisualState(nowNanos)
        if (!bounds.containsClose(
                mouseX,
                mouseY,
                visual.horizontalScale,
                visual.verticalScale,
                visual.verticalOffset,
                visual.closeScale,
            )
        ) {
            return false
        }
        customCopyHintDismissed = true
        customCopyHintDismissStartedAtNanos = nowNanos
        EmotionSoundEngine.playInterfaceClick()
        EmotifyClientConfig.dismissCustomCopyHint()
        return true
    }

    private fun shouldShowCustomHint(): Boolean =
        isCustomSection() && !customCopyHintDismissed && notice == null

    private fun shouldRenderCustomHint(nowNanos: Long): Boolean {
        if (!isCustomSection() || notice != null) {
            return false
        }
        if (!customCopyHintDismissed) {
            return true
        }
        return !reducedMotion &&
            customCopyHintDismissStartedAtNanos != Long.MIN_VALUE &&
            !EmotionPickerHintAnimation.isDismissFinished(customHintDismissAgeMillis(nowNanos))
    }

    private fun customHintText(): String {
        return fittedNoticeMessage(
            Component.translatable("screen.emotify.custom_hint.message").string,
            EmotionPickerHintLayout.maximumTextWidth(width, geometry.panelWidth),
        )
    }

    private fun customHintBounds(text: String, nowNanos: Long): EmotionPickerHintBounds {
        if (customCopyHintStartedAtNanos == Long.MIN_VALUE) {
            customCopyHintStartedAtNanos = nowNanos
        }
        return EmotionPickerHintLayout.bounds(
            width,
            height,
            geometry.panelX,
            geometry.panelY,
            geometry.panelWidth,
            geometry.panelHeight,
            font.width(text),
            font.lineHeight,
        )
    }

    private fun customHintAgeMillis(nowNanos: Long): Double =
        (nowNanos - customCopyHintStartedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

    private fun customHintDismissAgeMillis(nowNanos: Long): Double =
        (nowNanos - customCopyHintDismissStartedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND

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
            EmotionPickerMovement.release(minecraft)
        }
    }

    private fun createModel(pickerContext: EmotionPickerContext): EmotionPickerModel =
        EmotionPickerModel.from(
            pickerContext.allowedEmotions,
            favorites.snapshot,
            CustomEmojiRegistry.presentations(),
        ) { presentation ->
            presentation.literalName ?: Component.translatable(presentation.translationKey).string
        }

    private fun loadQuickSlotsSnapshot(): ClientConfigurationSnapshot = ClientConfigurationSnapshot.create(
        EmotifyClientConfig.settings(),
        EmotifyClientConfig.loadFavorites(),
        EmotifyClientConfig.loadQuickSlots(),
    )

    private fun EmotionPickerSection.tabIcon(): EmotionTabIcon = when (kind) {
        EmotionPickerSectionKind.FAVORITES -> EmotionTabIcon.FAVORITES
        EmotionPickerSectionKind.GROUP -> EmotionTabIcon.NONE
        EmotionPickerSectionKind.SEARCH -> EmotionTabIcon.SEARCH
    }

    companion object {
        private val NO_FAVORITES_MESSAGE = Component.translatable("screen.emotify.no_favorites")
        private val NO_SEARCH_RESULTS_MESSAGE = Component.translatable("screen.emotify.no_search_results")
        private val NO_EMOTIONS_MESSAGE = Component.translatable("message.emotify.no_emotions")
        private val NO_CUSTOM_EMOJIS_MESSAGE = Component.translatable("screen.emotify.no_custom_emojis")
        private const val MAXIMUM_SEARCH_LENGTH = 64
        private const val TRUNCATION_MARK = ".."
        private const val NO_QUICK_SLOT = -1
        private const val MAXIMUM_DRAG_FRAME_SECONDS = 0.05
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }

    private data class PickerWidgetState(
        val scrollAmount: Double,
        val searchFocused: Boolean,
    )

    private class CustomHintVisualState(
        var panelOpacity: Double = 1.0,
        var textOpacity: Double = 1.0,
        var closeOpacity: Double = 1.0,
        var horizontalScale: Double = 1.0,
        var verticalScale: Double = 1.0,
        var verticalOffset: Double = 0.0,
        var textHorizontalOffset: Double = 0.0,
        var closeScale: Double = 1.0,
    )

    private class EmotionDragSession(
        val presentation: EmotionPresentation,
        val originX: Double,
        val originY: Double,
        var pointerX: Double,
        var pointerY: Double,
        val motion: EmotionPickerDragPreview.Motion,
        var lastFrameNanos: Long,
        var dragging: Boolean = false,
        var dragStartedNanos: Long = Long.MIN_VALUE,
    ) {
        fun startDragging(startedNanos: Long) {
            dragging = true
            dragStartedNanos = startedNanos
            lastFrameNanos = startedNanos
        }
    }
}
