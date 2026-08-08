package me.whish.emotify.client.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64
import java.util.UUID

@Suppress("unused")
class IgnoredPlayerIdentityCodecTest : FunSpec({
    val uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    test("codec preserves UUID and Unicode offline name") {
        val identity = IgnoredPlayerIdentity.of(uuid, "Игрок_1")

        IgnoredPlayerIdentityCodec.decode(IgnoredPlayerIdentityCodec.encode(identity)) shouldBe identity
    }

    test("codec rejects malformed UUID base64 and UTF-8") {
        shouldThrow<IllegalArgumentException> {
            IgnoredPlayerIdentityCodec.decode("not-a-uuid:UGxheWVy")
        }
        shouldThrow<IllegalArgumentException> {
            IgnoredPlayerIdentityCodec.decode("$uuid:***")
        }
        val malformedUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        shouldThrow<Exception> {
            IgnoredPlayerIdentityCodec.decode("$uuid:$malformedUtf8")
        }
    }

    test("nullable decoder is safe for config validation") {
        IgnoredPlayerIdentityCodec.decodeOrNull(42).shouldBeNull()
        IgnoredPlayerIdentityCodec.decodeOrNull("$uuid:").shouldBeNull()
    }
})
