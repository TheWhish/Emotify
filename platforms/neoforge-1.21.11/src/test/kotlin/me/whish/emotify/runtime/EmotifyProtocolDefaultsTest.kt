package me.whish.emotify.runtime

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.EmotionAnimation

@Suppress("unused")
class EmotifyProtocolDefaultsTest {
    @Test
    fun `server selection lock follows the complete emotion animation lifecycle`() {
        EmotifyProtocol.serverHello.cooldownMillis shouldBe EmotionAnimation.DURATION_MILLIS.toInt()
    }
}
