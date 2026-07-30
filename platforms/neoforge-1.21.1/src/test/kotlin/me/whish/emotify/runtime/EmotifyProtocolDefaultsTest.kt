package me.whish.emotify.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionAnimation

@Suppress("unused")
class EmotifyProtocolDefaultsTest : FunSpec({
    test("server selection lock follows the complete emotion animation lifecycle") {
        EmotifyProtocol.serverHello.cooldownMillis shouldBe EmotionAnimation.DURATION_MILLIS.toInt()
    }
})
