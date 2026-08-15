package me.whish.emotify.runtime

import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.EmotifyProtocolFeatures
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.ServerHello

object EmotifyProtocol {
    const val TRANSPORT_VERSION = "1"
    val SELECTION_COOLDOWN_MILLIS: Int = EmotionAnimation.DURATION_MILLIS.toInt()

    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, EmotifyProtocolFeatures.supported)
    val clientHello = ClientHello(capabilities)
    val serverHello = ServerHello(capabilities, SELECTION_COOLDOWN_MILLIS, BuiltInEmotionCatalog.catalog)
}
