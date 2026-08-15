package me.whish.emotify.client

import java.util.UUID
import me.whish.emotify.client.settings.ClientSettingsSnapshot
import me.whish.emotify.client.settings.EmotifyIgnoredPlayersGeometry
import me.whish.emotify.client.settings.EmotifySettingsFocusPolicy
import me.whish.emotify.client.settings.EmotifySettingsGeometry
import me.whish.emotify.client.settings.EmotifySettingsLayout
import me.whish.emotify.client.settings.EmotifyUiBounds
import me.whish.emotify.client.settings.IgnoredPlayerIdentity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence

abstract class EmotifySettingsBackgroundScreen(
    title: Component,
) : Screen(title) {
    final override fun renderBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        renderEmotifyBackground(guiGraphics, mouseX, mouseY, partialTick)
    }

    final override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val handled = super.mouseClicked(mouseX, mouseY, button)
        if (EmotifySettingsFocusPolicy.shouldClear(handled, button, focused != null, focused is AbstractButton)) {
            clearFocus()
            return true
        }
        return handled
    }

    final override fun isPauseScreen(): Boolean = false

    protected abstract fun renderEmotifyBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    )
}

class EmotifySettingsScreen(
    private val parent: Screen?,
) : EmotifySettingsBackgroundScreen(Component.translatable("screen.emotify.settings")) {
    private var draft = EmotifyClientConfig.settings()
    private lateinit var geometry: EmotifySettingsGeometry
    private lateinit var ignoredPlayersButton: EmotifySettingRowButton

    override fun init() {
        geometry = EmotifySettingsLayout.main(width, height)
        val rows = geometry.rows
        addRenderableWidget(
            settingRow(
                rows[0],
                "screen.emotify.settings.show_others",
                "screen.emotify.settings.show_others.description",
                { CommonComponents.optionStatus(draft.showOtherPlayers) },
                { draft.showOtherPlayers },
            ) {
                draft = draft.withShowOtherPlayers(!draft.showOtherPlayers)
            },
        )
        addRenderableWidget(
            settingRow(
                rows[1],
                "screen.emotify.settings.show_custom",
                "screen.emotify.settings.show_custom.description",
                { CommonComponents.optionStatus(draft.showCustomEmotions) },
                { draft.showCustomEmotions },
            ) {
                draft = draft.withShowCustomEmotions(!draft.showCustomEmotions)
            },
        )
        ignoredPlayersButton = settingRow(
            rows[2],
            "screen.emotify.settings.ignored_players",
            "screen.emotify.settings.ignored_players.description",
            ::ignoredPlayersButtonMessage,
            { draft.ignoredPlayers.isNotEmpty() },
        ) {
            minecraft?.setScreen(IgnoredPlayersScreen(this, draft))
        }
        addRenderableWidget(ignoredPlayersButton)
        addRenderableWidget(
            settingRow(
                rows[3],
                "screen.emotify.settings.reduced_motion",
                "screen.emotify.settings.reduced_motion.description",
                { CommonComponents.optionStatus(draft.reducedMotion) },
                { draft.reducedMotion },
            ) {
                draft = draft.withReducedMotion(!draft.reducedMotion)
            },
        )
        addRenderableWidget(
            EmotifyVolumeSlider(
                rows[4].x,
                rows[4].y,
                rows[4].width,
                rows[4].height,
                draft.soundVolumePercent,
            ) { volume ->
                draft = draft.withSoundVolumePercent(volume)
            },
        )
        addRenderableWidget(
            EmotifyTextButton(
                geometry.cancel.x,
                geometry.cancel.y,
                geometry.cancel.width,
                geometry.cancel.height,
                CommonComponents.GUI_CANCEL,
                primary = false,
                ::onClose,
            ),
        )
        addRenderableWidget(
            EmotifyTextButton(
                geometry.done.x,
                geometry.done.y,
                geometry.done.width,
                geometry.done.height,
                CommonComponents.GUI_DONE,
                primary = true,
                ::commitAndClose,
            ),
        )
    }

    override fun renderEmotifyBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        EmotionPickerTheme.renderPanel(
            guiGraphics,
            geometry.panel.x,
            geometry.panel.y,
            geometry.panel.width,
            geometry.panel.height,
        )
        EmotionPickerTheme.renderList(
            guiGraphics,
            geometry.list.x,
            geometry.list.y,
            geometry.list.width,
            geometry.list.height,
        )
        guiGraphics.drawString(
            font,
            title,
            geometry.panel.x + (geometry.panel.width - font.width(title)) / 2,
            geometry.centeredTitleY(font.lineHeight),
            EmotionPickerTheme.tabText,
            false,
        )
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    internal fun acceptIgnoredPlayers(settings: ClientSettingsSnapshot) {
        draft = settings
        if (::ignoredPlayersButton.isInitialized) {
            ignoredPlayersButton.refresh()
        }
    }

    private fun settingRow(
        bounds: EmotifyUiBounds,
        labelKey: String,
        descriptionKey: String,
        value: () -> Component,
        selected: () -> Boolean,
        onPressed: () -> Unit,
    ): EmotifySettingRowButton = EmotifySettingRowButton(
        bounds.x,
        bounds.y,
        bounds.width,
        bounds.height,
        Component.translatable(labelKey),
        Component.translatable(descriptionKey),
        value,
        selected,
        onPressed,
    )

    private fun ignoredPlayersButtonMessage(): Component = Component.translatable(
        "screen.emotify.settings.manage_ignored",
        draft.ignoredPlayers.size,
    )

    private fun commitAndClose() {
        ClientHandshakeController.applySettings(draft)
        minecraft?.setScreen(parent)
    }
}

private class IgnoredPlayersScreen(
    private val parent: EmotifySettingsScreen,
    initialSettings: ClientSettingsSnapshot,
) : EmotifySettingsBackgroundScreen(Component.translatable("screen.emotify.ignored_players")) {
    private var draft = initialSettings
    private var rows = emptyList<IgnoredPlayerRow>()
    private var visibleRows = emptyList<IgnoredPlayerRow>()
    private var pageMessage: Component? = null
    private var page = 0
    private var rowsPerPage = 1
    private var query = ""
    private lateinit var searchBox: EmotionSearchBox
    private val rowButtons = ArrayList<EmotifyPlayerRowButton>()
    private lateinit var previousButton: EmotifyTextButton
    private lateinit var nextButton: EmotifyTextButton
    private lateinit var geometry: EmotifyIgnoredPlayersGeometry
    private var emptyLines = emptyList<FormattedCharSequence>()
    private var notice: Component? = null

    override fun init() {
        rowButtons.clear()
        rows = collectRows()
        geometry = EmotifySettingsLayout.ignoredPlayers(width, height)
        rowsPerPage = geometry.rows.size
        updateEmptyLines()
        searchBox = EmotionSearchBox(
            font,
            geometry.search.x,
            geometry.search.y,
            geometry.search.width,
            geometry.search.height,
            Component.translatable("screen.emotify.ignored_players.search"),
        ).also { field ->
            field.setMaxLength(MAXIMUM_SEARCH_LENGTH)
            field.setHint(Component.translatable("screen.emotify.ignored_players.search_hint"))
            field.setValue(query)
            field.setResponder(::onSearchChanged)
        }
        addRenderableWidget(searchBox)
        geometry.rows.forEachIndexed { index, bounds ->
            val button = EmotifyPlayerRowButton(
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
            ) {
                toggleIgnoredPlayer(index)
            }
            rowButtons += addRenderableWidget(button)
        }
        previousButton = navigationButton(
            geometry.previous,
            "<",
            "screen.emotify.ignored_players.previous_page",
        ) {
            page = (page - 1).coerceAtLeast(0)
            updatePageWidgets()
        }
        addRenderableWidget(previousButton)
        nextButton = navigationButton(
            geometry.next,
            ">",
            "screen.emotify.ignored_players.next_page",
        ) {
            page = (page + 1).coerceAtMost(pageCount() - 1)
            updatePageWidgets()
        }
        addRenderableWidget(nextButton)
        addRenderableWidget(
            EmotifyTextButton(
                geometry.cancel.x,
                geometry.cancel.y,
                geometry.cancel.width,
                geometry.cancel.height,
                CommonComponents.GUI_CANCEL,
                primary = false,
                ::onClose,
            ),
        )
        addRenderableWidget(
            EmotifyTextButton(
                geometry.done.x,
                geometry.done.y,
                geometry.done.width,
                geometry.done.height,
                CommonComponents.GUI_DONE,
                primary = true,
                ::acceptAndClose,
            ),
        )
        updatePageWidgets()
    }

    override fun renderEmotifyBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        EmotionPickerTheme.renderPanel(
            guiGraphics,
            geometry.panel.x,
            geometry.panel.y,
            geometry.panel.width,
            geometry.panel.height,
        )
        EmotionPickerTheme.renderSearchField(
            guiGraphics,
            geometry.search.x,
            geometry.search.y,
            geometry.search.width,
            geometry.search.height,
            searchBox.isFocused,
        )
        EmotionPickerTheme.renderList(
            guiGraphics,
            geometry.list.x,
            geometry.list.y,
            geometry.list.width,
            geometry.list.height,
        )
        guiGraphics.drawString(
            font,
            title,
            geometry.panel.x + (geometry.panel.width - font.width(title)) / 2,
            geometry.centeredTitleY(font.lineHeight),
            EmotionPickerTheme.tabText,
            false,
        )
        if (rows.isEmpty()) {
            val emptyBounds = geometry.emptyState
            EmotionPickerTheme.renderButton(
                guiGraphics,
                emptyBounds.x,
                emptyBounds.y,
                emptyBounds.width,
                emptyBounds.height,
                EmotionPickerTheme.button,
            )
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        if (rows.isEmpty()) {
            renderEmptyMessage(guiGraphics)
        }
        renderStatus(guiGraphics)
    }

    override fun repositionElements() {
        val restoreSearchFocus = ::searchBox.isInitialized && searchBox.isFocused
        super.repositionElements()
        if (restoreSearchFocus) {
            setFocused(searchBox)
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    private fun acceptAndClose() {
        parent.acceptIgnoredPlayers(draft)
        minecraft?.setScreen(parent)
    }

    private fun navigationButton(
        bounds: EmotifyUiBounds,
        label: String,
        tooltipKey: String,
        onPressed: () -> Unit,
    ): EmotifyTextButton = EmotifyTextButton(
        bounds.x,
        bounds.y,
        bounds.width,
        bounds.height,
        Component.literal(label),
        primary = false,
        onPressed,
    ).also { button ->
        button.tooltip = Tooltip.create(Component.translatable(tooltipKey))
    }

    private fun renderEmptyMessage(guiGraphics: GuiGraphics) {
        val emptyBounds = geometry.emptyState
        val firstLineY = emptyBounds.y + (emptyBounds.height - emptyLines.size * EMPTY_LINE_HEIGHT) / 2 + 1
        emptyLines.forEachIndexed { index, line ->
            guiGraphics.drawString(
                font,
                line,
                emptyBounds.x + (emptyBounds.width - font.width(line)) / 2,
                firstLineY + index * EMPTY_LINE_HEIGHT,
                EmotionPickerTheme.secondaryTextOnPanel,
                false,
            )
        }
    }

    private fun renderStatus(guiGraphics: GuiGraphics) {
        val message = notice ?: pageMessage ?: return
        val maximumWidth = geometry.next.x - geometry.previous.right - STATUS_HORIZONTAL_PADDING
        val source = message.string
        val text = if (font.width(source) <= maximumWidth) {
            source
        } else {
            val availableWidth = (maximumWidth - font.width(TRUNCATION_MARK)).coerceAtLeast(0)
            font.plainSubstrByWidth(source, availableWidth).trimEnd() + TRUNCATION_MARK
        }
        guiGraphics.drawString(
            font,
            text,
            geometry.panel.x + (geometry.panel.width - font.width(text)) / 2,
            geometry.previous.y + (geometry.previous.height - font.lineHeight) / 2 + 1,
            if (notice == null) EmotionPickerTheme.secondaryTextOnList else EmotionPickerTheme.errorOnList,
            false,
        )
    }

    private fun onSearchChanged(updatedQuery: String) {
        if (query == updatedQuery) {
            return
        }
        query = updatedQuery
        page = 0
        notice = null
        refreshListWidgets()
    }

    private fun refreshListWidgets() {
        rows = collectRows()
        updateEmptyLines()
        updatePageWidgets()
    }

    private fun updateEmptyLines() {
        val message = if (query.isBlank()) EMPTY_MESSAGE else NO_RESULTS_MESSAGE
        val maximumWidth = geometry.emptyState.width.coerceAtLeast(1)
        emptyLines = java.util.List.copyOf(font.split(message, maximumWidth).take(MAXIMUM_EMPTY_LINES))
    }

    private fun updatePageWidgets() {
        val pages = pageCount()
        page = page.coerceIn(0, pages - 1)
        val firstRow = page * rowsPerPage
        visibleRows = rows.subList(firstRow, (firstRow + rowsPerPage).coerceAtMost(rows.size))
        pageMessage = if (pages > 1) {
            Component.translatable("screen.emotify.ignored_players.page", page + 1, pages)
        } else {
            null
        }
        updateRowButtons()
        previousButton.visible = pages > 1
        previousButton.active = page > 0
        nextButton.visible = pages > 1
        nextButton.active = page < pages - 1
    }

    private fun updateRowButtons() {
        rowButtons.forEachIndexed { index, button ->
            val row = visibleRows.getOrNull(index)
            if (row == null) {
                button.unbind()
                return@forEachIndexed
            }
            val ignored = draft.isPlayerIgnored(row.uuid, row.name)
            button.bind(row.displayName, ignoreButtonMessage(ignored), ignored)
        }
    }

    private fun toggleIgnoredPlayer(index: Int) {
        val row = visibleRows.getOrNull(index) ?: return
        val ignored = draft.isPlayerIgnored(row.uuid, row.name)
        val updated = draft.withPlayerIgnored(row.uuid, row.name, !ignored)
        if (updated === draft && !ignored) {
            notice = Component.translatable(
                "screen.emotify.ignored_players.capacity",
                ClientSettingsSnapshot.MAXIMUM_IGNORED_PLAYERS,
            )
        } else {
            draft = updated
            notice = null
        }
        updateRowButtons()
    }

    private fun collectRows(): List<IgnoredPlayerRow> {
        val minecraft = Minecraft.getInstance()
        val localUuid = minecraft.player?.uuid
        val onlinePlayerInfos = minecraft.connection?.onlinePlayers ?: emptyList()
        val ignoredUuids = HashSet<UUID>(draft.ignoredPlayers.size)
        val ignoredNames = HashSet<String>(draft.ignoredPlayers.size)
        draft.ignoredPlayers.forEach { identity ->
            ignoredUuids += identity.uuid
            ignoredNames += identity.normalizedName
        }
        val presentIgnoredUuids = HashSet<UUID>(draft.ignoredPlayers.size)
        val presentIgnoredNames = HashSet<String>(draft.ignoredPlayers.size)
        val searchQuery = query.trim()
        val visibleOnlineUuids = HashSet<UUID>(onlinePlayerInfos.size.coerceAtMost(MAXIMUM_ONLINE_PLAYERS))
        val online = ArrayList<IgnoredPlayerRow>(onlinePlayerInfos.size.coerceAtMost(MAXIMUM_ONLINE_PLAYERS))
        onlinePlayerInfos.forEach { info ->
            val profile = info.profile
            if (profile.id == localUuid) {
                return@forEach
            }
            if (profile.id in ignoredUuids) {
                presentIgnoredUuids += profile.id
            }
            IgnoredPlayerIdentity.normalizeObservedName(profile.name)?.let { normalizedName ->
                if (normalizedName in ignoredNames) {
                    presentIgnoredNames += normalizedName
                }
            }
            if (online.size >= MAXIMUM_ONLINE_PLAYERS ||
                searchQuery.isNotEmpty() && !profile.name.contains(searchQuery, ignoreCase = true) ||
                !visibleOnlineUuids.add(profile.id)
            ) {
                return@forEach
            }
            online += IgnoredPlayerRow(profile.id, profile.name, profile.name)
        }
        online.sortWith(ROW_COMPARATOR)
        val offline = draft.ignoredPlayers
            .asSequence()
            .filter { identity ->
                identity.uuid !in presentIgnoredUuids && identity.normalizedName !in presentIgnoredNames
            }
            .filter { identity -> searchQuery.isEmpty() || identity.name.contains(searchQuery, ignoreCase = true) }
            .map { identity ->
                IgnoredPlayerRow(
                    identity.uuid,
                    identity.name,
                    Component.translatable("screen.emotify.ignored_players.offline", identity.name).string,
                )
            }
            .sortedWith(ROW_COMPARATOR)
            .toList()
        val combined = ArrayList<IgnoredPlayerRow>(online.size + offline.size)
        combined.addAll(online)
        combined.addAll(offline)
        return java.util.List.copyOf(combined)
    }

    private fun ignoreButtonMessage(ignored: Boolean): Component = Component.translatable(
        if (ignored) {
            "screen.emotify.ignored_players.unignore"
        } else {
            "screen.emotify.ignored_players.ignore"
        },
    )

    private fun pageCount(): Int = ((rows.size + rowsPerPage - 1) / rowsPerPage).coerceAtLeast(1)

    companion object {
        private val ROW_COMPARATOR = compareBy<IgnoredPlayerRow, String>(String.CASE_INSENSITIVE_ORDER) { row ->
            row.name
        }
            .thenBy { row -> row.uuid }
        private val EMPTY_MESSAGE = Component.translatable("screen.emotify.ignored_players.empty")
        private val NO_RESULTS_MESSAGE = Component.translatable("screen.emotify.ignored_players.no_results")
        private const val MAXIMUM_SEARCH_LENGTH = 64
        private const val MAXIMUM_ONLINE_PLAYERS = 1_024
        private const val EMPTY_LINE_HEIGHT = 11
        private const val MAXIMUM_EMPTY_LINES = 2
        private const val STATUS_HORIZONTAL_PADDING = 8
        private const val TRUNCATION_MARK = ".."
    }
}

private data class IgnoredPlayerRow(
    val uuid: UUID,
    val name: String,
    val displayName: String,
)
