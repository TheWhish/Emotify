package me.whish.emotify.client.custom

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Path

@Suppress("unused")
class CustomEmojiSourceReuseTest : FunSpec({
    test("one changed source invokes the decoder only for that source") {
        val files = listOf(
            file("first.png"),
            file("second.png"),
            file("third.png"),
        )
        val currentFingerprint = CustomEmojiDirectoryFingerprint(
            listOf(
                fingerprint("first.png", 100L, 1L),
                fingerprint("second.png", 200L, 2L),
                fingerprint("third.png", 300L, 3L),
            ),
            false,
        )
        val previous = mapOf(
            files[0].path.normalize() to CustomEmojiSourceCacheEntry(
                currentFingerprint.entries[0],
                "cached-first",
            ),
            files[1].path.normalize() to CustomEmojiSourceCacheEntry(
                fingerprint("second.png", 199L, 2L),
                "cached-second",
            ),
            files[2].path.normalize() to CustomEmojiSourceCacheEntry(
                currentFingerprint.entries[2],
                "cached-third",
            ),
        )
        val decoderCalls = ArrayList<String>()

        val resolved = planCustomEmojiSourceReuse(files, currentFingerprint, previous).map { source ->
            source.resolve(
                reuse = { cached -> cached },
                load = { changed, _ ->
                    decoderCalls += changed.displayName
                    "decoded-${changed.displayName}"
                },
            )
        }

        decoderCalls shouldContainExactly listOf("second")
        resolved shouldContainExactly listOf("cached-first", "decoded-second", "cached-third")
    }

    test("removed sources select only their obsolete texture for release") {
        customEmojiTextureIdsToRelease(
            setOf("emotify_custom:first", "emotify_custom:second"),
            setOf("emotify_custom:second", "emotify_custom:third"),
        ) shouldBe setOf("emotify_custom:first")
    }
}) {
    companion object {
        private fun file(name: String): CustomEmojiFile = CustomEmojiFile(
            Path.of("emoji", name),
            name.substringBeforeLast('.'),
            16,
            CustomEmojiFileFormat.PNG,
        )

        private fun fingerprint(
            name: String,
            size: Long,
            lastModifiedMillis: Long,
        ): CustomEmojiDirectoryEntry = CustomEmojiDirectoryEntry(name, size, lastModifiedMillis)
    }
}
