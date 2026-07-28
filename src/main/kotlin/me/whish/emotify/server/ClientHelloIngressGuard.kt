package me.whish.emotify.server

import me.whish.emotify.protocol.ClientHello

enum class ClientHelloIngressDecision(
    val shouldForward: Boolean,
) {
    FORWARD_INITIAL(true),
    DROP_DUPLICATE(false),
    FORWARD_CHANGED(true),
    DROP_BLOCKED(false),
}

class ClientHelloIngressGuard {
    private var initial: ClientHello? = null
    private var blocked = false

    fun evaluate(hello: ClientHello): ClientHelloIngressDecision {
        if (blocked) {
            return ClientHelloIngressDecision.DROP_BLOCKED
        }

        val first = initial
        if (first == null) {
            initial = hello
            return ClientHelloIngressDecision.FORWARD_INITIAL
        }
        if (first == hello) {
            return ClientHelloIngressDecision.DROP_DUPLICATE
        }

        blocked = true
        return ClientHelloIngressDecision.FORWARD_CHANGED
    }
}
