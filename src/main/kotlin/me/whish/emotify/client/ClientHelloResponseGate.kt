package me.whish.emotify.client

class ClientHelloResponseGate {
    private var activeConnectionId = NO_CONNECTION
    private var responded = false

    fun begin(connectionId: Long) {
        require(connectionId > NO_CONNECTION)
        activeConnectionId = connectionId
        responded = false
    }

    fun hasResponded(connectionId: Long): Boolean =
        activeConnectionId == connectionId && responded

    fun tryRespond(connectionId: Long): Boolean {
        if (activeConnectionId != connectionId || responded) {
            return false
        }
        responded = true
        return true
    }

    fun cancelResponse(connectionId: Long) {
        if (activeConnectionId == connectionId) {
            responded = false
        }
    }

    fun disconnect(connectionId: Long) {
        if (activeConnectionId != connectionId) {
            return
        }
        activeConnectionId = NO_CONNECTION
        responded = false
    }

    private companion object {
        const val NO_CONNECTION = 0L
    }
}
