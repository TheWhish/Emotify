package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import me.whish.emotify.client.custom.CustomEmojiEmbeddedDescriptorCodec
import me.whish.emotify.client.custom.CustomEmojiFileFormat
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiId

@Suppress("unused")
class CustomEmojiEmbeddedDescriptorTest : FunSpec({
    val descriptor = CustomEmojiDescriptor.create("Dance", CustomEmojiId(1L, 2L, 3L))
    val textData = "emotify\u0000${CustomEmojiEmbeddedDescriptorCodec.encode(descriptor)}"
        .toByteArray(StandardCharsets.ISO_8859_1)

    test("reads a descriptor before the PNG end chunk") {
        val bytes = PNG_SIGNATURE + pngChunk("tEXt", textData) + pngChunk("IEND")

        CustomEmojiEmbeddedDescriptor.read(CustomEmojiFileFormat.PNG, bytes) shouldBe descriptor
    }

    test("ignores descriptor chunks appended after PNG end") {
        val bytes = PNG_SIGNATURE + pngChunk("IEND") + pngChunk("tEXt", textData)

        CustomEmojiEmbeddedDescriptor.read(CustomEmojiFileFormat.PNG, bytes) shouldBe null
    }

    test("rejects overflowing PNG chunk lengths") {
        val bytes = ByteBuffer.allocate(PNG_SIGNATURE.size + 12)
            .put(PNG_SIGNATURE)
            .putInt(Int.MAX_VALUE)
            .put("tEXt".toByteArray(StandardCharsets.US_ASCII))
            .putInt(0)
            .array()

        CustomEmojiEmbeddedDescriptor.read(CustomEmojiFileFormat.PNG, bytes) shouldBe null
    }

    test("rejects an invalid PNG signature") {
        val bytes = PNG_SIGNATURE.copyOf().also { signature -> signature[0] = 0 } +
            pngChunk("tEXt", textData) + pngChunk("IEND")

        CustomEmojiEmbeddedDescriptor.read(CustomEmojiFileFormat.PNG, bytes) shouldBe null
    }
})

private fun pngChunk(type: String, data: ByteArray = byteArrayOf()): ByteArray =
    ByteBuffer.allocate(data.size + 12)
        .putInt(data.size)
        .put(type.toByteArray(StandardCharsets.US_ASCII))
        .put(data)
        .putInt(0)
        .array()

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)
