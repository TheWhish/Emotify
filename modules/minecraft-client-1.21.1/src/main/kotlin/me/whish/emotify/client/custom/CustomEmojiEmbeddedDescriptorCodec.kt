package me.whish.emotify.client.custom

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiId

object CustomEmojiEmbeddedDescriptorCodec {
    fun encode(descriptor: CustomEmojiDescriptor): String {
        val encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(
            descriptor.displayName.toByteArray(StandardCharsets.UTF_8),
        )
        return "$FORMAT_VERSION:${descriptor.originId.hexValue()}:$encodedName"
    }

    fun decode(encoded: String): CustomEmojiDescriptor? {
        if (encoded.length !in MINIMUM_ENCODED_LENGTH..MAXIMUM_ENCODED_LENGTH) {
            return null
        }
        val firstSeparator = encoded.indexOf(':')
        val secondSeparator = encoded.indexOf(':', firstSeparator + 1)
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1 || encoded.indexOf(':', secondSeparator + 1) >= 0) {
            return null
        }
        if (encoded.substring(0, firstSeparator) != FORMAT_VERSION) {
            return null
        }
        val originId = CustomEmojiId.parseHex(encoded.substring(firstSeparator + 1, secondSeparator)) ?: return null
        val nameBytes = try {
            Base64.getUrlDecoder().decode(encoded.substring(secondSeparator + 1))
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (nameBytes.size !in 1..CustomEmojiDescriptor.MAXIMUM_DISPLAY_NAME_UTF8_BYTES) {
            return null
        }
        val displayName = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(nameBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return try {
            CustomEmojiDescriptor.create(displayName, originId)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val FORMAT_VERSION = "1"
    private const val MINIMUM_ENCODED_LENGTH = 53
    private const val MAXIMUM_ENCODED_LENGTH = 224
}
