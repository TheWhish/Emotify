package me.whish.emotify.domain

import kotlin.math.min

data class ProtocolVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major in U8_RANGE) { "Protocol major must fit U8: $major" }
        require(minor in U8_RANGE) { "Protocol minor must fit U8: $minor" }
    }

    companion object {
        private val U8_RANGE = 0..255

        val CURRENT = ProtocolVersion(major = 1, minor = 6)
    }
}

@JvmInline
value class FeatureFlags(val bits: Long) {
    infix fun intersect(other: FeatureFlags): FeatureFlags = FeatureFlags(bits and other.bits)

    fun contains(feature: ProtocolFeature): Boolean = bits and feature.bit != 0L

    companion object {
        val NONE = FeatureFlags(0L)
    }
}

object EmotifyProtocolFeatures {
    val CUSTOM_EMOJI_SHARING = ProtocolFeature(bit = 1L, minimumMinor = 2)
    val ANIMATED_CUSTOM_EMOJI_SHARING = ProtocolFeature(bit = 1L shl 1, minimumMinor = 3)
    val LOSSLESS_CUSTOM_EMOJI_ASSETS = ProtocolFeature(bit = 1L shl 2, minimumMinor = 4)
    val THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE = ProtocolFeature(bit = 1L shl 3, minimumMinor = 5)
    val CUSTOM_EMOJI_DESCRIPTORS = ProtocolFeature(bit = 1L shl 4, minimumMinor = 6)
    val registry = ProtocolFeatureRegistry.of(
        CUSTOM_EMOJI_SHARING,
        ANIMATED_CUSTOM_EMOJI_SHARING,
        LOSSLESS_CUSTOM_EMOJI_ASSETS,
        THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE,
        CUSTOM_EMOJI_DESCRIPTORS,
    )
    val supported = registry.supportedAt(ProtocolVersion.CURRENT.minor)

    fun supportsAnimatedCustomEmojiSharing(features: FeatureFlags): Boolean =
        supportsCustomEmojiSharing(features) &&
            features.contains(ANIMATED_CUSTOM_EMOJI_SHARING) &&
            features.contains(THREE_SECOND_ANIMATED_CUSTOM_EMOJI_CYCLE)

    fun supportsCustomEmojiSharing(features: FeatureFlags): Boolean =
        features.contains(CUSTOM_EMOJI_SHARING) && features.contains(CUSTOM_EMOJI_DESCRIPTORS)

    fun supportsLosslessCustomEmojiSharing(features: FeatureFlags): Boolean =
        supportsCustomEmojiSharing(features) && features.contains(LOSSLESS_CUSTOM_EMOJI_ASSETS)
}

data class ProtocolCapabilities(
    val version: ProtocolVersion,
    val features: FeatureFlags,
)

data class ProtocolFeature(
    val bit: Long,
    val minimumMinor: Int,
) {
    init {
        require(bit.countOneBits() == 1) { "Protocol feature must use exactly one bit: $bit" }
        require(minimumMinor in 0..255) { "Minimum protocol minor must fit U8: $minimumMinor" }
    }
}

class ProtocolFeatureRegistry private constructor(source: Collection<ProtocolFeature>) {
    private val features: List<ProtocolFeature> = java.util.List.copyOf(source)

    init {
        require(features.map(ProtocolFeature::bit).toSet().size == features.size) {
            "Protocol feature bits must be unique"
        }
    }

    fun supportedAt(minor: Int): FeatureFlags {
        var supportedBits = 0L
        features.forEach { feature ->
            if (feature.minimumMinor <= minor) {
                supportedBits = supportedBits or feature.bit
            }
        }
        return FeatureFlags(supportedBits)
    }

    companion object {
        val EMPTY = ProtocolFeatureRegistry(emptyList())

        fun of(vararg features: ProtocolFeature): ProtocolFeatureRegistry =
            ProtocolFeatureRegistry(features.asList())
    }
}

sealed interface ProtocolNegotiation {
    data class Supported(
        val version: ProtocolVersion,
        val features: FeatureFlags,
    ) : ProtocolNegotiation

    data class Unsupported(
        val localMajor: Int,
        val remoteMajor: Int,
    ) : ProtocolNegotiation
}

object ProtocolNegotiator {
    fun negotiate(
        local: ProtocolCapabilities,
        remote: ProtocolCapabilities,
        registry: ProtocolFeatureRegistry,
    ): ProtocolNegotiation {
        if (local.version.major != remote.version.major) {
            return ProtocolNegotiation.Unsupported(local.version.major, remote.version.major)
        }

        val negotiatedVersion = ProtocolVersion(
            major = local.version.major,
            minor = min(local.version.minor, remote.version.minor),
        )
        val negotiatedFeatures = local.features
            .intersect(remote.features)
            .intersect(registry.supportedAt(negotiatedVersion.minor))

        return ProtocolNegotiation.Supported(negotiatedVersion, negotiatedFeatures)
    }
}
