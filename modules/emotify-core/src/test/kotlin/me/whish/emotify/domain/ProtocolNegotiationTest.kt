package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

@Suppress("unused")
class ProtocolNegotiationTest : FunSpec({
    test("current feature set enables bounded custom emoji sharing") {
        ProtocolVersion.CURRENT shouldBe ProtocolVersion(1, 6)
        EmotifyProtocolFeatures.supported.contains(EmotifyProtocolFeatures.CUSTOM_EMOJI_SHARING) shouldBe true
        EmotifyProtocolFeatures.supported.contains(EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING) shouldBe true
        EmotifyProtocolFeatures.supported.contains(EmotifyProtocolFeatures.LOSSLESS_CUSTOM_EMOJI_ASSETS) shouldBe true
        EmotifyProtocolFeatures.supported.contains(
            EmotifyProtocolFeatures.THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE,
        ) shouldBe true
        EmotifyProtocolFeatures.supported.contains(EmotifyProtocolFeatures.CUSTOM_EMOJI_DESCRIPTORS) shouldBe true
        EmotifyProtocolFeatures.registry.supportedAt(0) shouldBe FeatureFlags.NONE
        EmotifyProtocolFeatures.registry.supportedAt(1) shouldBe FeatureFlags.NONE
    }

    test("major mismatch is unsupported") {
        val local = ProtocolCapabilities(ProtocolVersion(1, 0), FeatureFlags.NONE)
        val remote = ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE)

        ProtocolNegotiator.negotiate(local, remote, ProtocolFeatureRegistry.EMPTY) shouldBe
            ProtocolNegotiation.Unsupported(localMajor = 1, remoteMajor = 2)
    }

    test("minor negotiation chooses the lower version") {
        val local = ProtocolCapabilities(ProtocolVersion(1, 4), FeatureFlags.NONE)
        val remote = ProtocolCapabilities(ProtocolVersion(1, 2), FeatureFlags.NONE)

        ProtocolNegotiator.negotiate(local, remote, ProtocolFeatureRegistry.EMPTY) shouldBe
            ProtocolNegotiation.Supported(ProtocolVersion(1, 2), FeatureFlags.NONE)
    }

    test("custom emoji sharing is disabled for protocol 1 point 1 peers") {
        val local = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val remote = ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags(1L))

        ProtocolNegotiator.negotiate(local, remote, EmotifyProtocolFeatures.registry) shouldBe
            ProtocolNegotiation.Supported(ProtocolVersion(1, 1), FeatureFlags.NONE)
    }

    test("animated custom emojis require protocol 1 point 3 on both peers") {
        val local = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val remote = ProtocolCapabilities(ProtocolVersion(1, 2), EmotifyProtocolFeatures.supported)

        val negotiated = ProtocolNegotiator.negotiate(local, remote, EmotifyProtocolFeatures.registry)
            as ProtocolNegotiation.Supported

        negotiated.features.contains(EmotifyProtocolFeatures.CUSTOM_EMOJI_SHARING) shouldBe true
        negotiated.features.contains(EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING) shouldBe false
    }

    test("lossless large custom emojis require protocol one point four on both peers") {
        val local = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val remote = ProtocolCapabilities(ProtocolVersion(1, 3), EmotifyProtocolFeatures.supported)

        val negotiated = ProtocolNegotiator.negotiate(local, remote, EmotifyProtocolFeatures.registry)
            as ProtocolNegotiation.Supported

        negotiated.features.contains(EmotifyProtocolFeatures.CUSTOM_EMOJI_SHARING) shouldBe true
        negotiated.features.contains(EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING) shouldBe true
        negotiated.features.contains(EmotifyProtocolFeatures.LOSSLESS_CUSTOM_EMOJI_ASSETS) shouldBe false
    }

    test("three second animated custom emojis require protocol one point five on both peers") {
        val local = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
        val remote = ProtocolCapabilities(ProtocolVersion(1, 4), EmotifyProtocolFeatures.supported)

        val negotiated = ProtocolNegotiator.negotiate(local, remote, EmotifyProtocolFeatures.registry)
            as ProtocolNegotiation.Supported

        negotiated.features.contains(EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING) shouldBe true
        negotiated.features.contains(
            EmotifyProtocolFeatures.THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE,
        ) shouldBe false
        EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(negotiated.features) shouldBe false
    }

    test("derived custom emoji capabilities reject orphan feature bits") {
        val orphanFeatures = FeatureFlags(
            EmotifyProtocolFeatures.ANIMATED_CUSTOM_EMOJI_SHARING.bit or
                EmotifyProtocolFeatures.LOSSLESS_CUSTOM_EMOJI_ASSETS.bit or
                EmotifyProtocolFeatures.THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE.bit,
        )

        EmotifyProtocolFeatures.supportsCustomEmojiSharing(orphanFeatures) shouldBe false
        EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(orphanFeatures) shouldBe false
        EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(orphanFeatures) shouldBe false
        EmotifyProtocolFeatures.supportsAnimatedCustomEmojiSharing(EmotifyProtocolFeatures.supported) shouldBe true
        EmotifyProtocolFeatures.supportsLosslessCustomEmojiSharing(EmotifyProtocolFeatures.supported) shouldBe true
    }

    test("features require both peers recognition and negotiated minor") {
        val registry = ProtocolFeatureRegistry.of(
            ProtocolFeature(bit = 1L, minimumMinor = 0),
            ProtocolFeature(bit = 2L, minimumMinor = 1),
            ProtocolFeature(bit = 4L, minimumMinor = 2),
        )
        val local = ProtocolCapabilities(ProtocolVersion(1, 3), FeatureFlags(0b111L))
        val remote = ProtocolCapabilities(ProtocolVersion(1, 1), FeatureFlags(0b111L))

        ProtocolNegotiator.negotiate(local, remote, registry) shouldBe
            ProtocolNegotiation.Supported(ProtocolVersion(1, 1), FeatureFlags(0b011L))
    }

    test("duplicate feature bits fail fast") {
        shouldThrow<IllegalArgumentException> {
            ProtocolFeatureRegistry.of(
                ProtocolFeature(bit = 1L, minimumMinor = 0),
                ProtocolFeature(bit = 1L, minimumMinor = 1),
            )
        }
    }

    test("same-major negotiation is symmetric") {
        checkAll(Arb.int(0..255), Arb.int(0..255), Arb.long(), Arb.long()) {
                localMinor,
                remoteMinor,
                localBits,
                remoteBits,
            ->
            val registry = ProtocolFeatureRegistry.of(
                ProtocolFeature(bit = 1L, minimumMinor = 0),
                ProtocolFeature(bit = 2L, minimumMinor = 128),
            )
            val local = ProtocolCapabilities(ProtocolVersion(1, localMinor), FeatureFlags(localBits))
            val remote = ProtocolCapabilities(ProtocolVersion(1, remoteMinor), FeatureFlags(remoteBits))

            ProtocolNegotiator.negotiate(local, remote, registry) shouldBe
                ProtocolNegotiator.negotiate(remote, local, registry)
        }
    }
})
