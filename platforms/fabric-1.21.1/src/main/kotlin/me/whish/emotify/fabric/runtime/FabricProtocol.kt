package me.whish.emotify.fabric.runtime

import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionAnimation
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.ServerHello

object FabricProtocol {
    val selectionCooldownMillis: Int = EmotionAnimation.DURATION_MILLIS.toInt()
    val capabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val clientHello = ClientHello(capabilities)
    val serverHello = ServerHello(capabilities, selectionCooldownMillis, BuiltInEmotionCatalog.catalog)
}
