package me.whish.emotify.fabric.server

import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.UUID

internal class FabricDeferredConnectionOpenQueue {
    private var connections = LinkedHashMap<UUID, DeferredConnectionOpen>()

    val size: Int
        get() = connections.size

    fun defer(playerId: UUID, connectionIdentity: Any, attemptsRemaining: Int = 1) {
        require(attemptsRemaining > 0) { "Deferred connection attempts must be positive: $attemptsRemaining" }
        connections[playerId] = DeferredConnectionOpen(
            WeakReference(connectionIdentity),
            attemptsRemaining,
        )
    }

    fun drain(visitor: (UUID, Any, Int) -> Unit) {
        if (connections.isEmpty()) return
        val drained = connections
        connections = LinkedHashMap()
        for (entry in drained) {
            val connectionIdentity = entry.value.connectionReference.get() ?: continue
            visitor(entry.key, connectionIdentity, entry.value.attemptsRemaining)
        }
    }

    fun clear() {
        connections.clear()
    }

    private data class DeferredConnectionOpen(
        val connectionReference: WeakReference<Any>,
        val attemptsRemaining: Int,
    )
}

