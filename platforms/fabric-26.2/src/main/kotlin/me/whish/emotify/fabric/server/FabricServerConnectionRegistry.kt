package me.whish.emotify.fabric.server

import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.server.core.ClientHelloIngressGuard
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.SelectionIngressGuard

class FabricServerConnectionState(
    val connectionId: ConnectionId,
    connectionIdentity: Any,
    val clientHelloGuard: ClientHelloIngressGuard = ClientHelloIngressGuard(),
    val selectionIngressGuard: SelectionIngressGuard = SelectionIngressGuard(SystemMonotonicTimeSource),
) {
    private val connectionReference = WeakReference(connectionIdentity)

    fun belongsTo(connectionIdentity: Any): Boolean = connectionReference.get() === connectionIdentity
}

object FabricServerConnectionRegistry {
    private val connectionIds = AtomicLong()
    private val connections = ConcurrentHashMap<UUID, FabricServerConnectionState>()

    fun open(playerId: UUID, connectionIdentity: Any): FabricServerConnectionState {
        val connectionId = connectionIds.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Server connection ID space is exhausted" }
            current + 1L
        }
        val state = FabricServerConnectionState(ConnectionId.of(connectionId), connectionIdentity)
        check(connections.putIfAbsent(playerId, state) == null) {
            "Fabric connection is already open for player $playerId"
        }
        return state
    }

    fun current(playerId: UUID): FabricServerConnectionState? = connections[playerId]

    fun current(playerId: UUID, connectionIdentity: Any): FabricServerConnectionState? =
        connections[playerId]?.takeIf { state -> state.belongsTo(connectionIdentity) }

    fun close(playerId: UUID, expected: FabricServerConnectionState): Boolean =
        connections.remove(playerId, expected)

    fun clear() {
        connections.clear()
    }

    val size: Int
        get() = connections.size
}

