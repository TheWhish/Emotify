package me.whish.emotify.client.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object IgnoredPlayerIdentityCodec {
    fun encode(identity: IgnoredPlayerIdentity): String {
        val encodedName = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(identity.name.toByteArray(StandardCharsets.UTF_8))
        return "${identity.uuid}:$encodedName"
    }

    fun decode(value: String): IgnoredPlayerIdentity {
        require(value.length <= MAXIMUM_ENCODED_LENGTH) { "Ignored player identity is too large" }
        val separator = value.indexOf(SEPARATOR)
        require(separator == UUID_TEXT_LENGTH) { "Invalid ignored player identity" }
        val encodedUuid = value.substring(0, separator)
        val uuid = UUID.fromString(encodedUuid)
        require(uuid.toString().equals(encodedUuid, ignoreCase = true)) { "Non-canonical ignored player UUID" }
        val encodedName = value.substring(separator + 1)
        require(encodedName.isNotEmpty()) { "Ignored player name is missing" }
        val nameBytes = Base64.getUrlDecoder().decode(encodedName)
        require(nameBytes.size <= IgnoredPlayerIdentity.MAXIMUM_NAME_UTF8_BYTES) {
            "Ignored player name is too large"
        }
        val canonicalName = Base64.getUrlEncoder().withoutPadding().encodeToString(nameBytes)
        require(canonicalName == encodedName) { "Non-canonical ignored player name encoding" }
        val name = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(nameBytes))
            .toString()
        return IgnoredPlayerIdentity.of(uuid, name)
    }

    fun decodeOrNull(value: Any?): IgnoredPlayerIdentity? {
        val encoded = value as? String ?: return null
        return try {
            decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private const val UUID_TEXT_LENGTH = 36
    private const val MAXIMUM_ENCODED_LENGTH = 128
    private const val SEPARATOR = ':'
}
