package me.whish.emotify.client.settings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import me.whish.emotify.client.picker.EmotionPickerLayoutMetrics

@Suppress("unused")
class EmotifySettingsLayoutTest : FunSpec({
    test("main settings panel expands symmetrically and packs six rows") {
        val layout = EmotifySettingsLayout.main(300, 300)

        layout.panel shouldBe EmotifyUiBounds(27, 38, 246, 223)
        layout.list shouldBe EmotifyUiBounds(33, 55, 234, 176)
        layout.rows shouldContainExactly listOf(
            EmotifyUiBounds(39, 61, 222, 24),
            EmotifyUiBounds(39, 89, 222, 24),
            EmotifyUiBounds(39, 117, 222, 24),
            EmotifyUiBounds(39, 145, 222, 24),
            EmotifyUiBounds(39, 173, 222, 24),
            EmotifyUiBounds(39, 201, 222, 24),
        )
        layout.cancel shouldBe EmotifyUiBounds(33, 235, 115, 20)
        layout.done shouldBe EmotifyUiBounds(152, 235, 115, 20)
        layout.cancel.right + 4 shouldBe layout.done.x
        layout.done.right shouldBe layout.list.right
        layout.panel.y shouldBe (300 - layout.panel.height) / 2
        300 - layout.panel.bottom shouldBe layout.panel.y + 1
        layout.rows.zipWithNext().forEach { (current, next) ->
            next.y - current.bottom shouldBe EmotifySettingsVisualMetrics.GAP
        }
        layout.rows.first().y - layout.list.y shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
        layout.list.bottom - layout.rows.last().bottom shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
    }

    test("settings title uses the picker title area without an extra content gap") {
        val main = EmotifySettingsLayout.main(300, 300)
        val ignored = EmotifySettingsLayout.ignoredPlayers(300, 300)

        EmotifySettingsVisualMetrics.CONTENT_TOP shouldBe EmotionPickerLayoutMetrics.TAB_Y_OFFSET
        main.centeredTitleY(9) - main.panel.y shouldBe 5
        ignored.centeredTitleY(9) - ignored.panel.y shouldBe 5
        main.list.y - main.panel.y shouldBe EmotifySettingsVisualMetrics.CONTENT_TOP
        ignored.search.y - ignored.panel.y shouldBe EmotifySettingsVisualMetrics.CONTENT_TOP
    }

    test("ignored-player screen keeps the compact frame and shared width") {
        val settings = EmotifySettingsLayout.main(300, 300)
        val layout = EmotifySettingsLayout.ignoredPlayers(300, 300)

        layout.panel shouldBe EmotifyUiBounds(27, 66, 246, 167)
        layout.search shouldBe EmotifyUiBounds(33, 83, 234, 18)
        layout.list shouldBe EmotifyUiBounds(33, 105, 234, 98)
        layout.emptyState shouldBe EmotifyUiBounds(39, 111, 222, 86)
        layout.rowViewport shouldBe EmotifyUiBounds(39, 111, 222, 68)
        layout.rows shouldContainExactly listOf(
            EmotifyUiBounds(39, 111, 222, 20),
            EmotifyUiBounds(39, 135, 222, 20),
            EmotifyUiBounds(39, 159, 222, 20),
        )
        layout.previous shouldBe EmotifyUiBounds(39, 183, 20, 18)
        layout.next shouldBe EmotifyUiBounds(241, 183, 20, 18)
        layout.panel.width shouldBe settings.panel.width
    }

    test("ignored-player empty state fills the list with uniform insets") {
        val layout = EmotifySettingsLayout.ignoredPlayers(300, 300)

        layout.emptyState.x - layout.list.x shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
        layout.emptyState.y - layout.list.y shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
        layout.list.right - layout.emptyState.right shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
        layout.list.bottom - layout.emptyState.bottom shouldBe EmotifySettingsVisualMetrics.CONTENT_PADDING
    }

    test("ignored-player viewport stays fixed and paginates three rows") {
        val layout = EmotifySettingsLayout.ignoredPlayers(300, 300)

        layout.rows.size shouldBe 3
        layout.rows.zipWithNext().forEach { (current, next) ->
            next.y - current.bottom shouldBe EmotifySettingsVisualMetrics.GAP
        }
        layout.previous.y - layout.rows.last().bottom shouldBe EmotifySettingsVisualMetrics.GAP
        layout.list.bottom - layout.previous.bottom shouldBe EmotifySettingsVisualMetrics.FRAME_THICKNESS
    }

    test("narrow screens keep controls ordered and inside the panel") {
        val main = EmotifySettingsLayout.main(200, 180)
        val ignored = EmotifySettingsLayout.ignoredPlayers(200, 180)

        main.panel.width shouldBe 196
        main.panel shouldBe EmotifyUiBounds(2, 2, 196, 176)
        main.rows.size shouldBe 6
        main.rows.forEach { row ->
            (row.x >= main.panel.x) shouldBe true
            (row.right <= main.panel.right) shouldBe true
            (row.y >= main.list.y) shouldBe true
            (row.bottom <= main.list.bottom) shouldBe true
        }
        main.rows.map(EmotifyUiBounds::height).distinct() shouldBe listOf(16)
        main.rows.zipWithNext().forEach { (current, next) ->
            next.y - current.bottom shouldBe EmotifySettingsVisualMetrics.GAP
        }
        main.cancel.bottom shouldBe main.panel.bottom - EmotifySettingsVisualMetrics.CONTENT_PADDING
        ignored.panel.width shouldBe 196
        ignored.rows.size shouldBe 3
        ignored.emptyState shouldBe EmotifyUiBounds(14, 51, 172, 86)
        ignored.cancel.x shouldBe ignored.list.x
        ignored.cancel.right + 4 shouldBe ignored.done.x
        ignored.done.right shouldBe ignored.list.right
        ignored.panel shouldBe EmotifyUiBounds(2, 6, 196, 167)
    }

    test("very narrow screens never force the panel outside the viewport width") {
        val main = EmotifySettingsLayout.main(150, 180)
        val ignored = EmotifySettingsLayout.ignoredPlayers(150, 180)

        main.panel shouldBe EmotifyUiBounds(2, 2, 146, 176)
        main.panel.right shouldBe 148
        ignored.panel shouldBe EmotifyUiBounds(2, 6, 146, 167)
        ignored.emptyState shouldBe EmotifyUiBounds(14, 51, 122, 86)
    }

    test("volume contents form one vertically balanced group") {
        val row = EmotifySettingsLayout.main(300, 300).rows.last()
        val textTop = row.y + EmotifySettingsVisualMetrics.VOLUME_TEXT_TOP
        val textBottom = textTop + 9
        val trackTop = row.bottom - EmotifySettingsVisualMetrics.VOLUME_TRACK_BOTTOM_INSET
        val thumbTop = trackTop - EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET
        val thumbBottom = trackTop + EmotifySettingsVisualMetrics.VOLUME_TRACK_HEIGHT +
            EmotifySettingsVisualMetrics.VOLUME_THUMB_VERTICAL_INSET

        textTop - row.y shouldBe 4
        thumbTop - textBottom shouldBe 1
        row.bottom - thumbBottom shouldBe 4
    }

    test("volume thumb keeps equal horizontal insets at both endpoints") {
        val row = EmotifySettingsLayout.main(300, 300).rows.last()

        EmotifyVolumeLayout.trackLeft(row.x) shouldBe 46
        EmotifyVolumeLayout.trackRight(row.x, row.width) shouldBe 254
        EmotifyVolumeLayout.thumbLeft(row.x, row.width, 0.0) shouldBe 46
        EmotifyVolumeLayout.thumbLeft(row.x, row.width, 0.5) shouldBe 148
        EmotifyVolumeLayout.thumbLeft(row.x, row.width, 1.0) shouldBe 249
    }

    test("pointer focus clears after buttons and blank primary clicks only") {
        EmotifySettingsFocusPolicy.shouldClear(false, 0, true, false) shouldBe true
        EmotifySettingsFocusPolicy.shouldClear(true, 0, true, true) shouldBe true
        EmotifySettingsFocusPolicy.shouldClear(true, 0, true, false) shouldBe false
        EmotifySettingsFocusPolicy.shouldClear(true, 0, false, true) shouldBe false
        EmotifySettingsFocusPolicy.shouldClear(false, 0, false, false) shouldBe false
        EmotifySettingsFocusPolicy.shouldClear(false, 1, true, false) shouldBe false
        EmotifySettingsFocusPolicy.shouldClear(true, 2, true, true) shouldBe false
    }
})
