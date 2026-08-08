package me.whish.emotify.server.core

import java.util.UUID
import kotlin.time.Duration
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolFeatureRegistry

class ServerSessionRegistry(
    private val serverCapabilities: ProtocolCapabilities,
    private var selectionCooldown: Duration,
    private val timeSource: MonotonicTimeSource,
    private val featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
    private val customAssets: ServerCustomAssetStore = ServerCustomAssetStore(),
    private val customAssetIngressBudget: CustomAssetIngressBudget = CustomAssetIngressBudget(timeSource = timeSource),
) {
    private val sessions = HashMap<UUID, Entry>()
    val size: Int
        get() = sessions.size

    val refreshableCount: Int
        get() = sessions.values.count { entry -> entry.session.handshakeState !is ServerHandshakeState.Unsupported }

    fun open(connection: ConnectionKey): ServerPlayerSession {
        val active = sessions[connection.playerId]
        check(active?.connection?.connectionId != connection.connectionId) {
            "Server session is already active for $connection"
        }

        val session = ServerPlayerSession(
            serverCapabilities,
            selectionCooldown,
            timeSource,
            featureRegistry,
            customAssets,
            customAssetIngressBudget,
        )
        active?.session?.close()
        sessions[connection.playerId] = Entry(connection, session)
        return session
    }

    fun get(connection: ConnectionKey): ServerPlayerSession? {
        return get(connection.playerId, connection.connectionId)
    }

    fun get(playerId: UUID, connectionId: ConnectionId): ServerPlayerSession? {
        val active = sessions[playerId] ?: return null
        return active.session.takeIf { active.connection.connectionId == connectionId }
    }

    fun activeConnection(playerId: UUID): ConnectionKey? = sessions[playerId]?.connection

    fun reconfigureSelectionCooldown(selectionCooldown: Duration) {
        sessions.values.forEach { entry -> entry.session.reconfigureSelectionCooldown(selectionCooldown) }
        this.selectionCooldown = selectionCooldown
    }

    fun clearCustomAssetRejections() {
        sessions.values.forEach { entry -> entry.session.clearCustomAssetRejections() }
    }

    fun visitRefreshable(visitor: (ConnectionKey) -> Unit) {
        sessions.values.forEach { entry ->
            if (entry.session.handshakeState !is ServerHandshakeState.Unsupported) {
                visitor(entry.connection)
            }
        }
    }

    fun isRefreshable(connection: ConnectionKey): Boolean {
        val session = get(connection) ?: return false
        return session.handshakeState !is ServerHandshakeState.Unsupported
    }

    fun close(connection: ConnectionKey): Boolean {
        val active = sessions[connection.playerId] ?: return false
        if (active.connection.connectionId != connection.connectionId) {
            return false
        }
        active.session.close()
        return sessions.remove(connection.playerId, active)
    }

    fun clear(): Int {
        val closedSessions = sessions.size
        sessions.values.forEach { entry -> entry.session.close() }
        sessions.clear()
        return closedSessions
    }

    private data class Entry(
        val connection: ConnectionKey,
        val session: ServerPlayerSession,
    )
}
