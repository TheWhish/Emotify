package me.whish.emotify.paper

import me.whish.emotify.paper.runtime.PaperConnectionIngress
import me.whish.emotify.paper.runtime.PaperDimensionOrdinalRegistry
import me.whish.emotify.paper.runtime.PaperGlobalTasks
import me.whish.emotify.paper.runtime.PaperRegionKey
import me.whish.emotify.protocol.RuntimeEntityId
import me.whish.emotify.server.core.AudiencePort
import me.whish.emotify.server.core.AudienceVisitCompletion
import me.whish.emotify.server.core.AudienceVisitor
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.PlayerSnapshot
import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player

internal class BukkitPaperPlayerSnapshotFactory(
    private val server: Server,
    private val connections: PaperConnectionIngress,
    private val dimensions: PaperDimensionOrdinalRegistry,
) {
    fun create(connection: ConnectionKey): PlayerSnapshot? {
        check(PaperGlobalTasks.isGlobalThread(server)) { "Paper player snapshots must be created on the global server thread" }
        val player = resolve(connection) ?: return null
        val entityId = RuntimeEntityId.parse(player.entityId) ?: return null
        return PlayerSnapshot(
            connection,
            entityId,
            alive = player.isOnline && player.isValid && !player.isDead,
            spectator = player.gameMode == GameMode.SPECTATOR,
            invisible = player.isInvisible,
            dimensionId = dimensions.resolve(player.world.uid),
            regionKey = PaperRegionKey.fromPosition(player.x, player.z),
            permittedToPublish = player.hasPermission(PaperPermissions.USE),
        )
    }

    private fun resolve(connection: ConnectionKey): Player? {
        val player = server.getPlayer(connection.playerId)
            ?.takeIf { candidate -> candidate.isOnline && candidate.uniqueId == connection.playerId }
            ?: return null
        return player.takeIf {
            connections.isActive(connection, player) && connections.isProtocolActive(connection)
        }
    }
}

internal class BukkitPaperAudiencePort(
    private val server: Server,
    private val connections: PaperConnectionIngress,
) : AudiencePort {
    override fun visitTracking(
        source: PlayerSnapshot,
        maxCandidates: Int,
        visitor: AudienceVisitor,
    ): AudienceVisitCompletion {
        check(PaperGlobalTasks.isGlobalThread(server)) { "Paper audience traversal must run on the global server thread" }
        require(maxCandidates > 0) { "Maximum audience candidate count must be positive: $maxCandidates" }
        val sourcePlayer = resolve(source.connection) ?: return AudienceVisitCompletion.EXHAUSTED
        var candidateCount = 0
        for (recipient in sourcePlayer.trackedBy) {
            if (recipient.uniqueId == source.connection.playerId) {
                continue
            }
            if (candidateCount >= maxCandidates) {
                return AudienceVisitCompletion.LIMIT_REACHED
            }
            candidateCount += 1
            if (!recipient.hasPermission(PaperPermissions.RECEIVE)) {
                continue
            }
            val recipientConnection = connections.current(recipient.uniqueId, recipient) ?: continue
            if (!connections.isProtocolActive(recipientConnection)) {
                continue
            }
            val sameDimension = sourcePlayer.world.uid == recipient.world.uid
            val distanceSquared = if (sameDimension) {
                distanceSquared(sourcePlayer, recipient)
            } else {
                Double.POSITIVE_INFINITY
            }
            if (!visitor.visit(
                    recipient.uniqueId,
                    recipientConnection.connectionId,
                    recipient.canSee(sourcePlayer),
                    sameDimension,
                    distanceSquared,
                )
            ) {
                return AudienceVisitCompletion.LIMIT_REACHED
            }
        }
        return AudienceVisitCompletion.EXHAUSTED
    }

    private fun resolve(connection: ConnectionKey): Player? {
        val player = server.getPlayer(connection.playerId)
            ?.takeIf { candidate -> candidate.isOnline && candidate.uniqueId == connection.playerId }
            ?: return null
        return player.takeIf {
            connections.isActive(connection, player) && connections.isProtocolActive(connection)
        }
    }

    private fun distanceSquared(first: Player, second: Player): Double {
        val x = first.x - second.x
        val y = first.y - second.y
        val z = first.z - second.z
        return x * x + y * y + z * z
    }
}
