package me.whish.emotify.protocol

import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion

object EmotifyProtocol {
    const val TRANSPORT_VERSION = "1"
    val SELECTION_COOLDOWN_MILLIS: Int = EmotionAnimation.DURATION_MILLIS.toInt()

    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val clientHello = ClientHello(capabilities)
    val serverHello = ServerHello(capabilities, SELECTION_COOLDOWN_MILLIS, EmotionCatalog.BUILT_IN)
}
