package me.whish.emotify.client

internal object EmotionLabelTruncation {
    fun completePrefix(source: String, prefix: String): String {
        require(source.startsWith(prefix)) { "Label prefix does not belong to its source" }
        val trimmed = prefix.trimEnd()
        val nextCharacter = source.getOrNull(prefix.length)
        if (prefix.lastOrNull()?.isWhitespace() == true || nextCharacter == null || nextCharacter.isWhitespace()) {
            return trimmed
        }
        val wordBoundary = trimmed.indexOfLast(Char::isWhitespace)
        if (wordBoundary < 0) {
            return trimmed
        }
        val fragment = trimmed.substring(wordBoundary + 1)
        val fragmentLength = fragment.codePointCount(0, fragment.length)
        return if (fragmentLength in 1 until MINIMUM_FRAGMENT_LENGTH) {
            trimmed.substring(0, wordBoundary).trimEnd()
        } else {
            trimmed
        }
    }

    private const val MINIMUM_FRAGMENT_LENGTH = 3
}

internal object EmotionPickerToggleGuard {
    fun shouldClose(matchesBinding: Boolean, bindingDown: Boolean): Boolean =
        matchesBinding && !bindingDown
}
