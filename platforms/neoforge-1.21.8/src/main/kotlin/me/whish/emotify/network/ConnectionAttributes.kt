package me.whish.emotify.network

import io.netty.util.AttributeKey
import me.whish.emotify.Emotify
import me.whish.emotify.server.ConnectionWorldEpoch
import me.whish.emotify.server.core.ClientHelloIngressGuard
import me.whish.emotify.server.core.SelectionIngressGuard

object ConnectionAttributes {
    val serverConnectionId: AttributeKey<Long> =
        AttributeKey.valueOf("${Emotify.ID}:server_connection_id")

    val serverWorldEpoch: AttributeKey<ConnectionWorldEpoch> =
        AttributeKey.valueOf("${Emotify.ID}:server_world_epoch")

    val clientHelloGuard: AttributeKey<ClientHelloIngressGuard> =
        AttributeKey.valueOf("${Emotify.ID}:client_hello_guard")

    val selectionIngressGuard: AttributeKey<SelectionIngressGuard> =
        AttributeKey.valueOf("${Emotify.ID}:selection_ingress_guard")
}
