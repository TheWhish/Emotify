package me.whish.emotify.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion

class HelloMessagesTest : FunSpec({
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)

    test("server hello accepts protocol limits") {
        ServerHello(capabilities, 250, EmotionCatalog.of(emptyList())).cooldownMillis shouldBe 250
        ServerHello(capabilities, 10_000, EmotionCatalog.BUILT_IN).cooldownMillis shouldBe 10_000
    }

    test("server hello rejects cooldown outside protocol limits") {
        shouldThrow<IllegalArgumentException> {
            ServerHello(capabilities, 249, EmotionCatalog.BUILT_IN)
        }
        shouldThrow<IllegalArgumentException> {
            ServerHello(capabilities, 10_001, EmotionCatalog.BUILT_IN)
        }
    }

    test("hello values compare structurally") {
        val first = ServerHello(
            capabilities,
            1_200,
            EmotionCatalog.of(listOf(EmotionId.of("emotify:happy"))),
        )
        val second = ServerHello(
            capabilities,
            1_200,
            EmotionCatalog.of(listOf(EmotionId.of("emotify:happy"))),
        )

        first shouldBe second
        ClientHello(capabilities) shouldBe ClientHello(capabilities)
    }

    test("server selection lock follows the complete emotion animation lifecycle") {
        EmotifyProtocol.serverHello.cooldownMillis shouldBe EmotionAnimation.DURATION_MILLIS.toInt()
    }
})
