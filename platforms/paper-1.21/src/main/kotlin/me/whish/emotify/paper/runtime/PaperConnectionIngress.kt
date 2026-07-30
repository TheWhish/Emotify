package me.whish.emotify.paper.runtime

import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.server.core.ClientHelloIngressGuard
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.GlobalSelectionIngressAdmission
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.GlobalSelectionIngressLease
import me.whish.emotify.server.core.SelectionIngressGuard
import me.whish.emotify.wire.v1.ProtocolV1Channels

sealed interface PaperClientHelloIngress {
    data class Admitted(val hello: ClientHello) : PaperClientHelloIngress

    data object DUPLICATE_OR_BLOCKED : PaperClientHelloIngress

    data object RATE_LIMITED : PaperClientHelloIngress

    data object STALE_CONNECTION : PaperClientHelloIngress
}

sealed interface PaperSelectionIngress {
    data class Admitted(
        val selection: EmotionSelection,
        val lease: GlobalSelectionIngressLease,
    ) : PaperSelectionIngress

    data object RATE_LIMITED : PaperSelectionIngress

    data object STALE_CONNECTION : PaperSelectionIngress

    data object UNKNOWN_EMOTION : PaperSelectionIngress
}

class PaperConnectionIngress(
    private val catalog: EmotionCatalog,
    private val timeSource: MonotonicTimeSource,
    private val globalSelections: GlobalSelectionIngressBudget = GlobalSelectionIngressBudget(
        timeSource = timeSource,
    ),
) {
    private val monitor = Any()
    private val entries = HashMap<UUID, Entry>()
    private var lastConnectionId = 0L

    fun begin(playerId: UUID, outgoingChannels: Collection<String> = emptyList()): ConnectionKey = synchronized(monitor) {
        lastConnectionId = Math.incrementExact(lastConnectionId)
        val connection = ConnectionKey(playerId, ConnectionId.of(lastConnectionId))
        entries[playerId] = Entry(
            connection,
            TokenBucket(HELLO_BURST_CAPACITY, HELLO_REFILL_TOKENS_PER_SECOND, timeSource),
            ClientHelloIngressGuard(),
            SelectionIngressGuard(timeSource),
            outgoingChannels.fold(0) { mask, channel -> mask or outgoingChannelBit(channel) },
        )
        connection
    }

    fun current(playerId: UUID): ConnectionKey? = synchronized(monitor) {
        entries[playerId]?.connection
    }

    fun isActive(connection: ConnectionKey): Boolean = synchronized(monitor) {
        entries[connection.playerId]?.connection == connection
    }

    fun activeConnections(): List<ConnectionKey> = synchronized(monitor) {
        entries.values.map { entry -> entry.connection }
    }

    fun registerOutgoingChannel(connection: ConnectionKey, channel: String): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        val bit = outgoingChannelBit(channel)
        if (bit == 0) {
            return false
        }
        entry.outgoingChannelsMask = entry.outgoingChannelsMask or bit
        true
    }

    fun unregisterOutgoingChannel(connection: ConnectionKey, channel: String): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        val bit = outgoingChannelBit(channel)
        if (bit == 0) {
            return false
        }
        entry.outgoingChannelsMask = entry.outgoingChannelsMask and bit.inv()
        true
    }

    fun supportsOutgoingChannel(connection: ConnectionKey, channel: String): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        val bit = outgoingChannelBit(channel)
        bit != 0 && entry.outgoingChannelsMask and bit == bit
    }

    fun supportsAllOutgoingChannels(connection: ConnectionKey): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        entry.outgoingChannelsMask and ALL_OUTGOING_CHANNELS_MASK == ALL_OUTGOING_CHANNELS_MASK
    }

    fun admitClientHello(
        connection: ConnectionKey,
        decode: () -> ClientHello,
    ): PaperClientHelloIngress {
        val entry = synchronized(monitor) {
            val active = entries[connection.playerId]
            if (active?.connection != connection) {
                return PaperClientHelloIngress.STALE_CONNECTION
            }
            if (!active.helloRequests.tryConsume()) {
                return PaperClientHelloIngress.RATE_LIMITED
            }
            active
        }
        val hello = decode()
        return synchronized(monitor) {
            if (entries[connection.playerId] !== entry) {
                return@synchronized PaperClientHelloIngress.STALE_CONNECTION
            }
            if (entry.helloGuard.evaluate(hello).shouldForward) {
                PaperClientHelloIngress.Admitted(hello)
            } else {
                PaperClientHelloIngress.DUPLICATE_OR_BLOCKED
            }
        }
    }

    fun admitSelection(
        connection: ConnectionKey,
        decode: () -> EmotionSelection,
    ): PaperSelectionIngress {
        val permit = synchronized(monitor) {
            val active = entries[connection.playerId]
            if (active?.connection != connection) {
                return PaperSelectionIngress.STALE_CONNECTION
            }
            if (!active.selectionGuard.tryAdmit()) {
                return PaperSelectionIngress.RATE_LIMITED
            }
            when (val admission = globalSelections.tryAcquire()) {
                is GlobalSelectionIngressAdmission.Admitted -> SelectionPermit(active, admission.lease)
                GlobalSelectionIngressAdmission.OutstandingLimitReached,
                GlobalSelectionIngressAdmission.RateLimited,
                -> return PaperSelectionIngress.RATE_LIMITED
            }
        }
        val selection = try {
            decode()
        } catch (exception: RuntimeException) {
            permit.lease.release()
            throw exception
        } catch (error: Error) {
            permit.lease.release()
            throw error
        }
        return synchronized(monitor) {
            if (entries[connection.playerId] !== permit.entry) {
                permit.lease.release()
                return@synchronized PaperSelectionIngress.STALE_CONNECTION
            }
            if (!catalog.contains(selection.emotionId)) {
                permit.lease.release()
                return@synchronized PaperSelectionIngress.UNKNOWN_EMOTION
            }
            PaperSelectionIngress.Admitted(selection, permit.lease)
        }
    }

    fun close(connection: ConnectionKey): Boolean = synchronized(monitor) {
        val active = entries[connection.playerId] ?: return@synchronized false
        if (active.connection != connection) {
            return@synchronized false
        }
        entries.remove(connection.playerId, active)
    }

    fun clear(): Int = synchronized(monitor) {
        val cleared = entries.size
        entries.clear()
        globalSelections.reset()
        cleared
    }

    private class Entry(
        val connection: ConnectionKey,
        val helloRequests: TokenBucket,
        val helloGuard: ClientHelloIngressGuard,
        val selectionGuard: SelectionIngressGuard,
        var outgoingChannelsMask: Int,
    )

    private data class SelectionPermit(
        val entry: Entry,
        val lease: GlobalSelectionIngressLease,
    )

    private companion object {
        const val HELLO_BURST_CAPACITY = 2
        const val HELLO_REFILL_TOKENS_PER_SECOND = 1
        const val SERVER_HELLO_CHANNEL_BIT = 1
        const val PLAY_CHANNEL_BIT = 1 shl 1
        const val SELECTION_REJECTED_CHANNEL_BIT = 1 shl 2
        const val ALL_OUTGOING_CHANNELS_MASK =
            SERVER_HELLO_CHANNEL_BIT or PLAY_CHANNEL_BIT or SELECTION_REJECTED_CHANNEL_BIT

        fun outgoingChannelBit(channel: String): Int = when (channel) {
            ProtocolV1Channels.SERVER_HELLO -> SERVER_HELLO_CHANNEL_BIT
            ProtocolV1Channels.PLAY -> PLAY_CHANNEL_BIT
            ProtocolV1Channels.SELECTION_REJECTED -> SELECTION_REJECTED_CHANNEL_BIT
            else -> 0
        }
    }
}
