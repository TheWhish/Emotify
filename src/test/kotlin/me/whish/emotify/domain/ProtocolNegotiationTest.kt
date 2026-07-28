package me.whish.emotify.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class ProtocolNegotiationTest : FunSpec({
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
