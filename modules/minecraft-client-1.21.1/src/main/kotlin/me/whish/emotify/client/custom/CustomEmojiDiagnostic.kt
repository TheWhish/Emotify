package me.whish.emotify.client.custom

enum class CustomEmojiDiagnosticReason(val translationKey: String) {
    INVALID_IMAGE("screen.emotify.custom_error.invalid_image.detail"),
    UNSUPPORTED_DIMENSIONS("screen.emotify.custom_error.unsupported_dimensions.detail"),
    FILE_TOO_LARGE("screen.emotify.custom_error.file_too_large.detail"),
    TOO_MANY_FRAMES("screen.emotify.custom_error.too_many_frames.detail"),
    DECODE_FAILED("screen.emotify.custom_error.decode_failed.detail"),
    DUPLICATE("screen.emotify.custom_error.duplicate.detail"),
    CAPACITY_REACHED("screen.emotify.custom_error.capacity_reached.detail"),
}

data class CustomEmojiDiagnostic(
    val displayName: String,
    val format: CustomEmojiFileFormat,
    val reason: CustomEmojiDiagnosticReason,
) {
    init {
        require(displayName.isNotBlank()) { "Custom emoji diagnostic display name cannot be blank" }
    }

    companion object {
        fun from(rejection: CustomEmojiFileRejection): CustomEmojiDiagnostic = CustomEmojiDiagnostic(
            rejection.displayName,
            rejection.format,
            when (rejection.reason) {
                CustomEmojiFileRejectionReason.INVALID_IMAGE -> CustomEmojiDiagnosticReason.INVALID_IMAGE
                CustomEmojiFileRejectionReason.UNSUPPORTED_DIMENSIONS ->
                    CustomEmojiDiagnosticReason.UNSUPPORTED_DIMENSIONS
                CustomEmojiFileRejectionReason.FILE_TOO_LARGE -> CustomEmojiDiagnosticReason.FILE_TOO_LARGE
            },
        )
    }
}
