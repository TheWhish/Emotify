package me.whish.emotify.domain

@JvmInline
value class EmotionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_ENCODED_LENGTH = 64

        private val PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9/._-]+")

        fun parse(value: String): EmotionId? {
            if (value.length !in 3..MAX_ENCODED_LENGTH || !PATTERN.matches(value)) {
                return null
            }

            return EmotionId(value)
        }

        fun of(value: String): EmotionId =
            requireNotNull(parse(value)) { "Invalid emotion ID: '$value'" }
    }
}
