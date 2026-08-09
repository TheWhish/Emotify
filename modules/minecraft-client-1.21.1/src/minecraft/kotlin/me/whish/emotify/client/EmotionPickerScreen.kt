package me.whish.emotify.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.math.Axis
import me.whish.emotify.client.picker.EmotionPickerBrand
import me.whish.emotify.client.picker.EmotionPickerContext
import me.whish.emotify.client.picker.EmotionPickerDragGesture
import me.whish.emotify.client.picker.EmotionPickerDragPreview
import me.whish.emotify.client.picker.EmotionPickerGeometry
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
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class EmotionPickerScreen(
    initialContext: EmotionPickerContext,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) : Screen(
    Component.literal(EmotionPickerBrand.TITLE),
) {
    private var context = initialContext
    private val favorites = FavoriteEmotionStore.from(EmotifyClientConfig.loadFavorites())
    private var quickSlots = loadQuickSlotsSnapshot()
    private val quickSlotInputGate = QuickSlotInputGate()
    private var model = createModel(initialContext)
    private var state = requireNotNull(model.initialState()) { "Emotion picker requires at least one section" }
    private var displayedEmotions = model.emotions(state)
    private var notice: EmotionPickerNotice? = null
    private lateinit var geometry: EmotionPickerGeometry
    private lateinit var grid: EmotionGridList
    private lateinit var searchBox: EmotionSearchBox
    private val quickSlotButtons = ArrayList<EmotionQuickSlotButton>()
    private var dragSession: EmotionDragSession? = null
    private var dragTargetSlot = NO_QUICK_SLOT
    private var reducedMotion = false
    private var retainedWidgetState: PickerWidgetState? = null

    override fun init() {
        val client = requireNotNull(minecraft) { "Minecraft client is unavailable" }
        cancelDrag()
        quickSlots = loadQuickSlotsSnapshot()
        reducedMotion = EmotifyClientConfig.settings().reducedMotion
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
                EmotionQuickSlotButton(
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
            ::beginEmotionPointerPress,
            ::isDragging,
        )
        grid.setX(geometry.listX)
        grid.replaceEmotions(displayedEmotions)
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
        val client = minecraft ?: run {
            onClose()
            return
        }
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
        if (displayedEmotions.isEmpty()) {
            val emptyMessage = when (model.section(state).kind) {
                EmotionPickerSectionKind.FAVORITES -> NO_FAVORITES_MESSAGE
                EmotionPickerSectionKind.SEARCH -> NO_SEARCH_RESULTS_MESSAGE
                EmotionPickerSectionKind.GROUP -> if (state.sectionId == EmotionPickerModel.CUSTOM_SECTION_ID) {
                    NO_CUSTOM_EMOJIS_MESSAGE
                } else {
                    NO_EMOTIONS_MESSAGE
                }
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
        val nowNanos = timeSource.nowNanos()
        notice?.let { current -> renderNotice(guiGraphics, current, nowNanos) }
        renderDragPreview(guiGraphics, mouseX, mouseY, nowNanos)
    }

    override fun isPauseScreen(): Boolean = false

    override fun added() {
        super.added()
        minecraft?.let { client ->
            EmotionPickerMovement.begin(client)
            if (allowsMovementInput()) {
                EmotionPickerMovement.update(client)
            } else {
                EmotionPickerMovement.release(client)
            }
            CustomEmojiRegistry.refreshIfChanged(client) {
                if (client.screen === this) {
                    applyPolicy(context)
                }
            }
        }
    }

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
        if (button == 0 && finishEmotionPointerPress(mouseX, mouseY)) {
            return true
        }
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

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val current = dragSession
        if (button == 0 && current != null) {
            current.pointerX = mouseX
            current.pointerY = mouseY
            if (!current.dragging && EmotionPickerDragGesture.shouldStart(
                    current.originX,
                    current.originY,
                    mouseX,
                    mouseY,
                )
            ) {
                current.startDragging(timeSource.nowNanos())
            }
            if (current.dragging) {
                dragTargetSlot = geometry.quickSlotAt(mouseX, mouseY)
            }
            return true
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val movementPressed = EmotionPickerMovement.keyPressed(minecraft, keyCode, scanCode)
        if (EmotionPickerController.matchesPickerKey(keyCode, scanCode)) {
            val textInputFocused = isSearchInputFocused()
            if (EmotionPickerController.shouldClosePicker(keyCode, scanCode, textInputFocused)) {
                onClose()
            }
            if (!textInputFocused) {
                return true
            }
        }
        val quickSlotInputIndex = QuickSlotKeyResolver.resolve(keyCode, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1)
        if (quickSlotInputIndex != QuickSlotKeyResolver.NO_SLOT) {
            val edgePress = quickSlotInputGate.press(quickSlotInputIndex)
            val quickSlotIndex = QuickSlotKeyResolver.slotIndex(quickSlotInputIndex)
            when (QuickSlotInputRouting.press(isSearchInputFocused(), edgePress)) {
                QuickSlotPressDecision.ACTIVATE_WITH_CLICK_FEEDBACK -> activateQuickSlotFromKeyboard(quickSlotIndex)
                QuickSlotPressDecision.CONSUME_REPEAT -> Unit
                QuickSlotPressDecision.DISPATCH_TO_TEXT_INPUT ->
                    return super.keyPressed(keyCode, scanCode, modifiers)
            }
            return true
        }
        if (EmotionPickerKeyboardRouting.consumePress(allowsMovementInput(), movementPressed)) {
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val movementReleased = EmotionPickerMovement.keyReleased(minecraft, keyCode, scanCode)
        val quickSlotInputIndex = QuickSlotKeyResolver.resolve(keyCode, GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_KP_1)
        if (quickSlotInputIndex != QuickSlotKeyResolver.NO_SLOT) {
            quickSlotInputGate.release(quickSlotInputIndex)
            if (!isSearchInputFocused()) {
                return true
            }
        }
        if (allowsMovementInput() && movementReleased) {
            return true
        }
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        cancelDrag()
        quickSlotInputGate.clear()
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

    private fun openSettings() {
        retainedWidgetState = captureWidgetState()
        minecraft?.setScreen(EmotifySettingsScreen(this))
    }

    private fun openEmojiFolder() {
        minecraft?.let { client ->
            CustomEmojiRegistry.openDirectory(client) {
                if (client.screen === this) {
                    showNotice(Component.translatable("message.emotify.emoji_folder_failed"))
                }
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
        grid.replaceEmotions(displayedEmotions)
    }

    private fun selectEmotion(presentation: EmotionPresentation) {
        val listener = minecraft?.connection
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
        val soundManager = minecraft?.soundManager
        if (button == null || soundManager == null) {
            selectQuickSlot(slotIndex)
            return
        }
        button.activateFromKeyboard(soundManager)
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
        notice = null
        rebuildWidgets()
        restoreWidgetState(widgetState)
    }

    private fun toggleFavorite(presentation: EmotionPresentation) {
        val availableIds = model.sections.last().emotions.map(EmotionPresentation::emotionId)
        val result = favorites.toggle(presentation.emotionId, availableIds)
        if (result != FavoriteToggleResult.ADDED && result != FavoriteToggleResult.REMOVED) {
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
        pose.pushPose()
        try {
            pose.translate(current.motion.x, current.motion.y, 0.0)
            pose.mulPose(Axis.ZP.rotationDegrees(tiltDegrees.toFloat()))
            pose.scale(scale.toFloat(), scale.toFloat(), 1.0F)
            pose.translate(-previewSize / 2.0, -previewSize / 2.0, 0.0)
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
                EmotionTextureResources.resolve(current.presentation.textureId),
                (previewSize - iconSize) / 2,
                (previewSize - iconSize) / 2,
                iconSize,
                iconSize,
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

    private fun synchronizeQuickSlotInput(client: Minecraft) {
        val window = client.window.window
        var physicallyPressedMask = 0
        repeat(ClientConfigurationSchema.QUICK_SLOT_COUNT) { index ->
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_1 + index)) {
                physicallyPressedMask = physicallyPressedMask or (1 shl index)
            }
            if (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_1 + index)) {
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
    }

    private data class PickerWidgetState(
        val scrollAmount: Double,
        val searchFocused: Boolean,
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
