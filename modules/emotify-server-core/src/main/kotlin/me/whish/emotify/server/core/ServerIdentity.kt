package me.whish.emotify.server.core

import java.util.UUID
import me.whish.emotify.protocol.RuntimeEntityId

@JvmInline
value class ConnectionId private constructor(val value: Long) {
    companion object {
        fun parse(value: Long): ConnectionId? = value.takeIf { it > 0L }?.let(::ConnectionId)

        fun of(value: Long): ConnectionId =
            requireNotNull(parse(value)) { "Connection ID must be positive: $value" }
    }
}

data class ConnectionKey(
    val playerId: UUID,
    val connectionId: ConnectionId,
)

data class PlayerSnapshot(
    val connection: ConnectionKey,
    val entityId: RuntimeEntityId,
    val alive: Boolean,
    val spectator: Boolean,
    val invisible: Boolean,
    val dimensionId: Int,
    val regionKey: Long,
    val permittedToPublish: Boolean = true,
) {
    val canPublish: Boolean
        get() = alive && !spectator && !invisible && permittedToPublish
}
