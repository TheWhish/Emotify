package me.whish.emotify.server

import java.util.UUID
import kotlin.time.Duration
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolFeatureRegistry

class ServerSessionRegistry(
    private val serverCapabilities: ProtocolCapabilities,
    private val selectionCooldown: Duration,
    private val timeSource: MonotonicTimeSource,
    private val featureRegistry: ProtocolFeatureRegistry = ProtocolFeatureRegistry.EMPTY,
) {
    private val sessions = HashMap<UUID, Entry>()

    val size: Int
        get() = sessions.size

    fun open(playerId: UUID, connectionId: Long): ServerPlayerSession {
        val active = sessions[playerId]
        check(active?.connectionId != connectionId) {
            "Server session is already active for player $playerId and connection $connectionId"
        }

        val session = ServerPlayerSession(
            serverCapabilities,
            selectionCooldown,
            timeSource,
            featureRegistry,
        )
        sessions[playerId] = Entry(connectionId, session)
        return session
    }

    fun get(playerId: UUID, connectionId: Long): ServerPlayerSession? {
        val active = sessions[playerId] ?: return null
        return active.session.takeIf { active.connectionId == connectionId }
    }

    fun close(playerId: UUID, connectionId: Long): Boolean {
        val active = sessions[playerId] ?: return false
        if (active.connectionId != connectionId) {
            return false
        }
        return sessions.remove(playerId, active)
    }

    fun clear() {
        sessions.clear()
    }

    private data class Entry(
        val connectionId: Long,
        val session: ServerPlayerSession,
    )
}
