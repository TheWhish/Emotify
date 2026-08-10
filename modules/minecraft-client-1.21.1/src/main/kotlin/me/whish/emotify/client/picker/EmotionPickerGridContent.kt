package me.whish.emotify.client.picker

import java.util.Locale
import me.whish.emotify.client.custom.CustomEmojiDiagnostic
import me.whish.emotify.client.presentation.EmotionPresentation

sealed interface EmotionPickerGridItem {
    data class Available(val presentation: EmotionPresentation) : EmotionPickerGridItem

    data class UnavailableCustom(val diagnostic: CustomEmojiDiagnostic) : EmotionPickerGridItem
}

object EmotionPickerGridContent {
    fun regular(emotions: Collection<EmotionPresentation>): List<EmotionPickerGridItem> =
        java.util.List.copyOf(emotions.map(EmotionPickerGridItem::Available))

    fun custom(
        emotions: Collection<EmotionPresentation>,
        diagnostics: Collection<CustomEmojiDiagnostic>,
    ): List<EmotionPickerGridItem> = java.util.List.copyOf(
        buildList(emotions.size + diagnostics.size) {
            emotions.mapTo(this, EmotionPickerGridItem::Available)
            diagnostics
                .sortedWith(
                    compareBy<CustomEmojiDiagnostic>(
                        { diagnostic -> diagnostic.displayName.lowercase(Locale.ROOT) },
                        CustomEmojiDiagnostic::displayName,
                        { diagnostic -> diagnostic.format.ordinal },
                    ),
                )
                .mapTo(this, EmotionPickerGridItem::UnavailableCustom)
        },
    )
}
