package me.whish.emotify.catalog.builtin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import me.whish.emotify.domain.EmotionId

@Suppress("unused")
class BuiltInEmotionCatalogTest : FunSpec({
    test("built in catalog follows the canonical atlas order") {
        BuiltInEmotionCatalog.catalog.ids.size shouldBe 162
        BuiltInEmotionCatalog.catalog.ids.take(6).map(EmotionId::value) shouldContainExactly listOf(
            "emotify:grinning_face",
            "emotify:beaming_face",
            "emotify:tears_of_joy",
            "emotify:rolling_laugh",
            "emotify:smiling_face",
            "emotify:happy",
        )
        BuiltInEmotionManifest.definitions.map(BuiltInEmotionDefinition::id) shouldContainExactly
            BuiltInEmotionCatalog.catalog.ids
    }

    test("built in manifest preserves attribution categories and default favorites") {
        BuiltInEmotionManifest.source shouldBe BuiltInEmotionSource(
            "Happy's Better Emojis",
            "Happy_AlexRO",
            "https://modrinth.com/resourcepack/happys-emojis",
            "CC-BY-4.0",
        )
        BuiltInEmotionManifest.categories.map { category -> category.id } shouldContainExactly listOf("faces", "animals")
        BuiltInEmotionManifest.defaultFavoriteIds.map(EmotionId::value) shouldContainExactly listOf(
            "emotify:happy",
            "emotify:sad",
            "emotify:angry",
            "emotify:surprised",
            "emotify:love",
            "emotify:confused",
        )
        BuiltInEmotionManifest.find(EmotionId.of("emotify:happy"))?.category shouldBe "faces"
        BuiltInEmotionManifest.find(EmotionId.of("other:unknown")) shouldBe null
        BuiltInEmotionManifest.findCategory("animals")?.translationKey shouldBe "category.emotify.animals"
        BuiltInEmotionManifest.findCategory("unknown") shouldBe null
    }

    test("built in manifest exposes immutable snapshots") {
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.definitions as MutableList).clear()
        }
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.categories as MutableList).clear()
        }
        shouldThrow<UnsupportedOperationException> {
            (BuiltInEmotionManifest.defaultFavoriteIds as MutableList).clear()
        }
    }

    test("built in manifest preserves the complete semantic digest") {
        semanticDigest() shouldBe "8FDB28E396ED6617D7D7E0D0AD5BA5D96092D6FC06B5F069FFC4284D964588F3"
    }
}) {
    companion object {
        private fun semanticDigest(): String {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { output ->
                output.writeInt(BuiltInEmotionManifest.SCHEMA_VERSION)
                output.writeCanonical(BuiltInEmotionManifest.source.name)
                output.writeCanonical(BuiltInEmotionManifest.source.author)
                output.writeCanonical(BuiltInEmotionManifest.source.url)
                output.writeCanonical(BuiltInEmotionManifest.source.license)
                output.writeInt(BuiltInEmotionManifest.categories.size)
                BuiltInEmotionManifest.categories.forEach { category ->
                    output.writeCanonical(category.id)
                    output.writeCanonical(category.translationKey)
                }
                output.writeInt(BuiltInEmotionManifest.defaultFavoriteIds.size)
                BuiltInEmotionManifest.defaultFavoriteIds.forEach { emotionId ->
                    output.writeCanonical(emotionId.value)
                }
                output.writeInt(BuiltInEmotionManifest.definitions.size)
                BuiltInEmotionManifest.definitions.forEach { definition ->
                    output.writeCanonical(definition.id.value)
                    output.writeCanonical(definition.texture)
                    output.writeCanonical(definition.translationKey)
                    output.writeCanonical(definition.category)
                    output.writeCanonical(definition.glyph)
                    output.writeInt(definition.sourceSlot)
                    output.writeInt(definition.region.x)
                    output.writeInt(definition.region.y)
                    output.writeInt(definition.region.width)
                    output.writeInt(definition.region.height)
                    output.writeInt(definition.region.textureWidth)
                    output.writeInt(definition.region.textureHeight)
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(buffer.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }

        private fun DataOutputStream.writeCanonical(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }
    }
}
