package me.whish.emotify.client.picker

import java.text.Normalizer
import java.util.Locale
import me.whish.emotify.catalog.builtin.BuiltInEmotionManifest
import me.whish.emotify.client.presentation.EmotionPresentation
import me.whish.emotify.client.presentation.EmotionPresentationCatalog
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

enum class EmotionPickerSectionKind {
    FAVORITES,
    GROUP,
    SEARCH,
}

class EmotionPickerSection(
    val id: String,
    val translationKey: String,
    val kind: EmotionPickerSectionKind,
    emotions: Collection<EmotionPresentation>,
) {
    val emotions: List<EmotionPresentation> = java.util.List.copyOf(emotions)

    init {
        require(id.isNotBlank()) { "Emotion picker section ID cannot be blank" }
        require(translationKey.isNotBlank()) { "Emotion picker section translation key cannot be blank" }
    }
}

data class EmotionPickerState(
    val sectionId: String,
    val firstVisibleRow: Int,
    val query: String = "",
)

class EmotionPickerModel private constructor(
    source: Collection<EmotionPickerSection>,
    searchDocuments: Map<EmotionId, String>,
) {
    val sections: List<EmotionPickerSection> = java.util.List.copyOf(source)

    private val sectionById: Map<String, EmotionPickerSection> = java.util.Map.copyOf(
        sections.associateBy(EmotionPickerSection::id),
    )
    private val searchDocumentByEmotionId: Map<EmotionId, String> = java.util.Map.copyOf(searchDocuments)

    init {
        require(sections.size == sectionById.size) { "Emotion picker sections must have unique IDs" }
        if (sections.isNotEmpty()) {
            require(sections.first().kind == EmotionPickerSectionKind.FAVORITES) {
                "Emotion picker favorites must be the first section"
            }
            require(sections.last().kind == EmotionPickerSectionKind.SEARCH) {
                "Emotion picker search must be the last section"
            }
        }
    }

    fun initialState(): EmotionPickerState? {
        val initialSection = sections.firstOrNull { section ->
            section.kind == EmotionPickerSectionKind.FAVORITES && section.emotions.isNotEmpty()
        } ?: sections.firstOrNull { section ->
            section.kind == EmotionPickerSectionKind.GROUP && section.emotions.isNotEmpty()
        } ?: sections.firstOrNull { section -> section.kind == EmotionPickerSectionKind.SEARCH }
        return initialSection?.let { section -> EmotionPickerState(section.id, 0) }
    }

    fun section(state: EmotionPickerState): EmotionPickerSection =
        checkNotNull(sectionById[state.sectionId]) { "Unknown emotion picker section: ${state.sectionId}" }

    fun emotions(state: EmotionPickerState): List<EmotionPresentation> {
        val selectedSection = section(state)
        if (selectedSection.kind != EmotionPickerSectionKind.SEARCH) {
            return selectedSection.emotions
        }
        val terms = normalizedSearchTerms(state.query)
        if (terms.isEmpty()) {
            return selectedSection.emotions
        }
        return java.util.List.copyOf(
            selectedSection.emotions.filter { presentation ->
                val document = searchDocumentByEmotionId.getValue(presentation.emotionId)
                terms.all(document::contains)
            },
        )
    }

    fun selectSection(state: EmotionPickerState, sectionId: String): EmotionPickerState =
        if (sectionId in sectionById) EmotionPickerState(sectionId, 0, state.query) else state

    fun updateQuery(state: EmotionPickerState, query: String): EmotionPickerState {
        section(state)
        return state.copy(firstVisibleRow = 0, query = query)
    }

    fun withFavorites(favoriteEmotions: Set<EmotionId>): EmotionPickerModel {
        if (sections.isEmpty()) {
            return this
        }
        val favorites = sections.last().emotions.filter { presentation ->
            presentation.emotionId in favoriteEmotions
        }
        val updatedSections = buildList(sections.size) {
            add(
                EmotionPickerSection(
                    FAVORITES_SECTION_ID,
                    "category.emotify.favorites",
                    EmotionPickerSectionKind.FAVORITES,
                    favorites,
                ),
            )
            addAll(sections.subList(1, sections.size))
        }
        return EmotionPickerModel(updatedSections, searchDocumentByEmotionId)
    }

    fun scrollRows(
        state: EmotionPickerState,
        deltaRows: Int,
        visibleRows: Int,
    ): EmotionPickerState {
        require(visibleRows > 0) { "Visible emotion row count must be positive" }
        val maxFirstRow = EmotionPickerGridLayout.maxFirstVisibleRow(
            emotions(state).size,
            visibleRows,
        )
        val requestedRow = state.firstVisibleRow.toLong() + deltaRows.toLong()
        return state.copy(firstVisibleRow = requestedRow.coerceIn(0L, maxFirstRow.toLong()).toInt())
    }

    fun visibleEmotions(
        state: EmotionPickerState,
        visibleRows: Int,
    ): List<EmotionPresentation> {
        val normalizedState = scrollRows(state, 0, visibleRows)
        val emotions = emotions(normalizedState)
        val firstIndex = normalizedState.firstVisibleRow * EmotionPickerGridLayout.COLUMNS
        val lastIndex = (firstIndex + visibleRows * EmotionPickerGridLayout.COLUMNS).coerceAtMost(emotions.size)
        return emotions.subList(firstIndex, lastIndex)
    }

    companion object {
        const val FAVORITES_SECTION_ID = "favorites"
        const val CUSTOM_SECTION_ID = "custom"
        const val SEARCH_SECTION_ID = "search"

        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val WHITESPACE = Regex("\\s+")

        fun from(
            allowedEmotions: EmotionCatalog,
            favoriteEmotions: Set<EmotionId> = BuiltInEmotionManifest.defaultFavoriteIds.toSet(),
            customEmojis: Collection<EmotionPresentation> = emptyList(),
            localizedName: (EmotionPresentation) -> String = EmotionPresentation::translationKey,
        ): EmotionPickerModel {
            val allowedBuiltIns = EmotionPresentationCatalog.ordered.filter { presentation ->
                allowedEmotions.contains(presentation.emotionId)
            }
            val customPresentations = customEmojis
                .asSequence()
                .filter { presentation -> presentation.category == CUSTOM_SECTION_ID }
                .distinctBy(EmotionPresentation::emotionId)
                .take(EmotionCatalog.MAX_SIZE)
                .toList()
            val allowedPresentations = allowedBuiltIns + customPresentations
            if (allowedPresentations.isEmpty()) {
                return EmotionPickerModel(emptyList(), emptyMap())
            }
            val sections = buildList {
                val favorites = allowedPresentations.filter { presentation ->
                    presentation.emotionId in favoriteEmotions
                }
                add(
                    EmotionPickerSection(
                        FAVORITES_SECTION_ID,
                        "category.emotify.favorites",
                        EmotionPickerSectionKind.FAVORITES,
                        favorites,
                    ),
                )
                EmotionPresentationCatalog.categories.forEach { category ->
                    val emotions = allowedBuiltIns.filter { presentation ->
                        presentation.category == category.id
                    }
                    if (emotions.isNotEmpty()) {
                        add(
                            EmotionPickerSection(
                                category.id,
                                category.translationKey,
                                EmotionPickerSectionKind.GROUP,
                                emotions,
                            ),
                        )
                    }
                }
                add(
                    EmotionPickerSection(
                        CUSTOM_SECTION_ID,
                        "category.emotify.custom",
                        EmotionPickerSectionKind.GROUP,
                        customPresentations,
                    ),
                )
                add(
                    EmotionPickerSection(
                        SEARCH_SECTION_ID,
                        "category.emotify.search",
                        EmotionPickerSectionKind.SEARCH,
                        allowedPresentations,
                    ),
                )
            }
            val searchDocuments = allowedPresentations.associate { presentation ->
                presentation.emotionId to normalizeSearchText(
                    "${localizedName(presentation)} ${presentation.emotionId.value}",
                )
            }
            return EmotionPickerModel(sections, searchDocuments)
        }

        private fun normalizedSearchTerms(query: String): List<String> {
            val normalized = normalizeSearchText(query)
            return if (normalized.isEmpty()) emptyList() else normalized.split(' ')
        }

        private fun normalizeSearchText(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
            val withoutMarks = COMBINING_MARKS.replace(decomposed, "")
            return WHITESPACE.replace(withoutMarks.lowercase(Locale.ROOT).trim(), " ")
        }
    }
}

object EmotionPickerGridLayout {
    const val COLUMNS = 3
    const val CELL_HEIGHT = 36
    const val ROW_GAP = 4
    const val ROW_STRIDE = CELL_HEIGHT + ROW_GAP

    fun rowCount(itemCount: Int): Int {
        require(itemCount >= 0) { "Emotion count cannot be negative" }
        return itemCount / COLUMNS + if (itemCount % COLUMNS == 0) 0 else 1
    }

    fun visibleRowCount(viewportHeight: Int): Int {
        require(viewportHeight > 0) { "Emotion viewport height must be positive" }
        return ((viewportHeight + ROW_GAP) / ROW_STRIDE).coerceAtLeast(1)
    }

    fun maxFirstVisibleRow(itemCount: Int, visibleRows: Int): Int {
        require(visibleRows > 0) { "Visible emotion row count must be positive" }
        return (rowCount(itemCount) - visibleRows).coerceAtLeast(0)
    }
}
