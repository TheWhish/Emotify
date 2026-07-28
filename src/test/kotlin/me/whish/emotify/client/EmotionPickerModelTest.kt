package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.BuiltInEmotionManifest
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId

class EmotionPickerModelTest : FunSpec({
    val happy = EmotionId.of("emotify:happy")
    val love = EmotionId.of("emotify:love")
    val surprised = EmotionId.of("emotify:surprised")
    val confused = EmotionId.of("emotify:confused")
    val sad = EmotionId.of("emotify:sad")
    val angry = EmotionId.of("emotify:angry")
    val dog = EmotionId.of("emotify:dog")
    val wolf = EmotionId.of("emotify:wolf")

    test("complete catalog creates pinned virtual tabs around ordered groups") {
        val model = EmotionPickerModel.from(
            EmotionCatalog.BUILT_IN,
            BuiltInEmotionManifest.defaultFavoriteIds.toSet(),
        )

        model.sections.map(EmotionPickerSection::id) shouldContainExactly listOf(
            EmotionPickerModel.FAVORITES_SECTION_ID,
            "faces",
            "animals",
            EmotionPickerModel.SEARCH_SECTION_ID,
        )
        model.sections.map(EmotionPickerSection::kind) shouldContainExactly listOf(
            EmotionPickerSectionKind.FAVORITES,
            EmotionPickerSectionKind.GROUP,
            EmotionPickerSectionKind.GROUP,
            EmotionPickerSectionKind.SEARCH,
        )
        model.sections.map { section -> section.emotions.size } shouldContainExactly listOf(6, 130, 32, 162)
        model.initialState() shouldBe EmotionPickerState(EmotionPickerModel.FAVORITES_SECTION_ID, 0)
    }

    test("favorites and groups retain canonical catalog order") {
        val model = EmotionPickerModel.from(
            EmotionCatalog.BUILT_IN,
            linkedSetOf(sad, happy, angry, confused, surprised, love),
        )

        model.sections.first().emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(
            happy,
            love,
            surprised,
            confused,
            sad,
            angry,
        )
        model.sections.first { section -> section.id == "faces" }
            .emotions
            .take(6)
            .map(EmotionPresentation::emotionId) shouldContainExactly listOf(
            EmotionId.of("emotify:grinning_face"),
            EmotionId.of("emotify:beaming_face"),
            EmotionId.of("emotify:tears_of_joy"),
            EmotionId.of("emotify:rolling_laugh"),
            EmotionId.of("emotify:smiling_face"),
            happy,
        )
    }

    test("favorite updates reuse groups and the prepared search index") {
        var localizedNames = 0
        val model = EmotionPickerModel.from(
            EmotionCatalog.of(listOf(happy, sad, dog)),
            emptySet(),
        ) { presentation ->
            localizedNames += 1
            presentation.translationKey
        }
        val localizedAfterBuild = localizedNames
        val updated = model.withFavorites(linkedSetOf(dog, happy))

        localizedNames shouldBe localizedAfterBuild
        updated.sections.first().emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy, dog)
        (updated.sections[1] === model.sections[1]) shouldBe true
        (updated.sections.last() === model.sections.last()) shouldBe true
    }

    test("server policy filters every tab without adopting server order") {
        val allowed = EmotionCatalog.of(listOf(wolf, happy, dog))
        val model = EmotionPickerModel.from(allowed, setOf(dog, happy))

        model.sections.map(EmotionPickerSection::id) shouldContainExactly listOf(
            EmotionPickerModel.FAVORITES_SECTION_ID,
            "faces",
            "animals",
            EmotionPickerModel.SEARCH_SECTION_ID,
        )
        model.sections[0].emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy, dog)
        model.sections[1].emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy)
        model.sections[2].emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(dog, wolf)
        model.sections[3].emotions.map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy, dog, wolf)
    }

    test("empty server policy produces no selectable state") {
        val model = EmotionPickerModel.from(
            EmotionCatalog.of(emptyList()),
            BuiltInEmotionManifest.defaultFavoriteIds.toSet(),
        )

        model.sections shouldBe emptyList()
        model.initialState() shouldBe null
    }

    test("empty favorites remain accessible while the first ordered group opens") {
        val model = EmotionPickerModel.from(EmotionCatalog.BUILT_IN, emptySet())

        model.sections.first().id shouldBe EmotionPickerModel.FAVORITES_SECTION_ID
        model.sections.first().emotions shouldBe emptyList()
        model.initialState() shouldBe EmotionPickerState("faces", 0)
    }

    test("favorites unavailable under server policy do not force an empty initial tab") {
        val model = EmotionPickerModel.from(
            EmotionCatalog.of(listOf(dog)),
            setOf(happy),
        )

        model.sections.map(EmotionPickerSection::id) shouldContainExactly listOf(
            EmotionPickerModel.FAVORITES_SECTION_ID,
            "animals",
            EmotionPickerModel.SEARCH_SECTION_ID,
        )
        model.initialState() shouldBe EmotionPickerState("animals", 0)
    }

    test("localized search matches normalized names and namespaced ids") {
        val localizedNames = mapOf(
            happy to "Радостное лицо",
            sad to "Очень грустное лицо",
            dog to "Собака",
        )
        val model = EmotionPickerModel.from(
            EmotionCatalog.of(listOf(dog, sad, happy)),
            emptySet(),
        ) { presentation -> localizedNames.getValue(presentation.emotionId) }
        val search = model.selectSection(requireNotNull(model.initialState()), EmotionPickerModel.SEARCH_SECTION_ID)

        model.emotions(model.updateQuery(search, "  РАДОСТНОЕ   ЛИЦО "))
            .map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy)
        model.emotions(model.updateQuery(search, "EMOTIFY:DOG"))
            .map(EmotionPresentation::emotionId) shouldContainExactly listOf(dog)
        model.emotions(model.updateQuery(search, "грустное emotify:sad"))
            .map(EmotionPresentation::emotionId) shouldContainExactly listOf(sad)
        model.emotions(model.updateQuery(search, "нет совпадений")) shouldBe emptyList()
        model.emotions(model.updateQuery(search, ""))
            .map(EmotionPresentation::emotionId) shouldContainExactly listOf(happy, sad, dog)
    }

    test("query state is immutable resets scroll and only filters search") {
        val model = EmotionPickerModel.from(EmotionCatalog.BUILT_IN, emptySet()) { presentation ->
            presentation.emotionId.value
        }
        val faces = model.selectSection(requireNotNull(model.initialState()), "faces")
        val scrolled = model.scrollRows(faces, 7, visibleRows = 4)
        val queriedFaces = model.updateQuery(scrolled, "dog")
        val search = model.selectSection(queriedFaces, EmotionPickerModel.SEARCH_SECTION_ID)

        scrolled shouldBe EmotionPickerState("faces", 7)
        queriedFaces shouldBe EmotionPickerState("faces", 0, "dog")
        model.emotions(queriedFaces).size shouldBe 130
        search shouldBe EmotionPickerState(EmotionPickerModel.SEARCH_SECTION_ID, 0, "dog")
        model.emotions(search).map(EmotionPresentation::emotionId) shouldContainExactly listOf(dog)
    }

    test("section changes reset scroll and unknown sections are ignored") {
        val model = EmotionPickerModel.from(EmotionCatalog.BUILT_IN)
        val initial = requireNotNull(model.initialState())
        val faces = model.selectSection(initial, "faces")
        val scrolled = model.scrollRows(faces, 7, visibleRows = 4)

        scrolled shouldBe EmotionPickerState("faces", 7)
        model.selectSection(scrolled, "animals") shouldBe EmotionPickerState("animals", 0)
        model.selectSection(scrolled, "unknown") shouldBe scrolled
    }

    test("scrolling is row based and clamped to filtered search results") {
        val model = EmotionPickerModel.from(EmotionCatalog.BUILT_IN) { presentation ->
            presentation.emotionId.value
        }
        val faces = model.selectSection(requireNotNull(model.initialState()), "faces")
        val search = model.selectSection(faces, EmotionPickerModel.SEARCH_SECTION_ID)
        val filtered = model.updateQuery(search, "cat")

        model.scrollRows(faces, 100, visibleRows = 4) shouldBe EmotionPickerState("faces", 40)
        model.scrollRows(EmotionPickerState("faces", 40), -100, visibleRows = 4) shouldBe
            EmotionPickerState("faces", 0)
        model.scrollRows(filtered, 100, visibleRows = 2).firstVisibleRow shouldBe
            EmotionPickerGridLayout.maxFirstVisibleRow(model.emotions(filtered).size, 2)
    }

    test("visible emotions contain only complete requested rows") {
        val model = EmotionPickerModel.from(EmotionCatalog.BUILT_IN)
        val faces = model.selectSection(requireNotNull(model.initialState()), "faces")
        val secondPage = model.scrollRows(faces, 2, visibleRows = 2)

        model.visibleEmotions(secondPage, visibleRows = 2) shouldContainExactly
            model.emotions(secondPage).subList(6, 12)
    }

    test("three column layout handles partial final rows") {
        EmotionPickerGridLayout.rowCount(0) shouldBe 0
        EmotionPickerGridLayout.rowCount(1) shouldBe 1
        EmotionPickerGridLayout.rowCount(3) shouldBe 1
        EmotionPickerGridLayout.rowCount(4) shouldBe 2
        EmotionPickerGridLayout.visibleRowCount(35) shouldBe 1
        EmotionPickerGridLayout.visibleRowCount(36) shouldBe 1
        EmotionPickerGridLayout.visibleRowCount(76) shouldBe 2
    }
})
