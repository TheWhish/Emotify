package me.whish.emotify.network

import io.netty.util.AttributeKey
import me.whish.emotify.Emotify
import me.whish.emotify.server.ClientHelloIngressGuard
import me.whish.emotify.server.SelectionIngressGuard

object ConnectionAttributes {
    val serverConnectionId: AttributeKey<Long> =
        AttributeKey.valueOf("${Emotify.ID}:server_connection_id")

    val clientHelloGuard: AttributeKey<ClientHelloIngressGuard> =
        AttributeKey.valueOf("${Emotify.ID}:client_hello_guard")

    val selectionIngressGuard: AttributeKey<SelectionIngressGuard> =
        AttributeKey.valueOf("${Emotify.ID}:selection_ingress_guard")
}
