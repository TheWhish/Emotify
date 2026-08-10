package me.whish.emotify.paper.runtime

import java.lang.ref.WeakReference
import java.util.UUID
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.protocol.ClientHello
import me.whish.emotify.protocol.CustomEmotionSelection
import me.whish.emotify.protocol.CustomEmojiAssetChunk
import me.whish.emotify.protocol.EmotionSelection
import me.whish.emotify.server.core.ClientHelloIngressGuard
import me.whish.emotify.server.core.ConnectionId
import me.whish.emotify.server.core.ConnectionKey
import me.whish.emotify.server.core.GlobalSelectionIngressAdmission
import me.whish.emotify.server.core.GlobalSelectionIngressBudget
import me.whish.emotify.server.core.GlobalSelectionIngressLease
import me.whish.emotify.server.core.SelectionIngressGuard
import me.whish.emotify.wire.v1.ProtocolV1Channels
import me.whish.emotify.wire.v1.ProtocolV1Limits
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker
import me.whish.emotify.wire.v1.CustomEmojiLosslessCodec

sealed interface PaperClientHelloIngress {
    data class Admitted(val hello: ClientHello) : PaperClientHelloIngress

    data object DUPLICATE_OR_BLOCKED : PaperClientHelloIngress

    data object PROTOCOL_INACTIVE : PaperClientHelloIngress

    data object RATE_LIMITED : PaperClientHelloIngress

    data object STALE_CONNECTION : PaperClientHelloIngress
}

sealed interface PaperSelectionIngress {
    data class Admitted(
        val selection: EmotionSelection,
        val lease: GlobalSelectionIngressLease,
    ) : PaperSelectionIngress

    data object RATE_LIMITED : PaperSelectionIngress

    data object PROTOCOL_INACTIVE : PaperSelectionIngress

    data object STALE_CONNECTION : PaperSelectionIngress

    data object UNKNOWN_EMOTION : PaperSelectionIngress
}

sealed interface PaperCustomSelectionIngress {
    data class Admitted(
        val selection: CustomEmotionSelection,
        val lease: GlobalSelectionIngressLease,
    ) : PaperCustomSelectionIngress

    data object RATE_LIMITED : PaperCustomSelectionIngress
    data object PROTOCOL_INACTIVE : PaperCustomSelectionIngress
    data object STALE_CONNECTION : PaperCustomSelectionIngress
    data object INVALID_SIZE : PaperCustomSelectionIngress
}

sealed interface PaperCustomAssetChunkIngress {
    data class Admitted(val chunk: CustomEmojiAssetChunk) : PaperCustomAssetChunkIngress
    data object RATE_LIMITED : PaperCustomAssetChunkIngress
    data object PROTOCOL_INACTIVE : PaperCustomAssetChunkIngress
    data object STALE_CONNECTION : PaperCustomAssetChunkIngress
    data object INVALID_SIZE : PaperCustomAssetChunkIngress
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
    private val customAssetBytes = TokenBucket(
        CUSTOM_ASSET_GLOBAL_BURST_BYTES,
        CUSTOM_ASSET_GLOBAL_REFILL_BYTES_PER_SECOND,
        timeSource,
    )

    fun begin(
        playerId: UUID,
        connectionIdentity: Any,
        outgoingChannels: Collection<String> = emptyList(),
    ): ConnectionKey = synchronized(monitor) {
        lastConnectionId = Math.incrementExact(lastConnectionId)
        val connection = ConnectionKey(playerId, ConnectionId.of(lastConnectionId))
        entries[playerId] = Entry(
            connection,
            connectionIdentity,
            TokenBucket(HELLO_BURST_CAPACITY, HELLO_REFILL_TOKENS_PER_SECOND, timeSource),
            ClientHelloIngressGuard(),
            SelectionIngressGuard(timeSource),
            TokenBucket(
                CUSTOM_SELECTION_CONNECTION_BURST_BYTES,
                CUSTOM_SELECTION_CONNECTION_REFILL_BYTES_PER_SECOND,
                timeSource,
            ),
            TokenBucket(CUSTOM_ASSET_CONNECTION_BURST_BYTES, CUSTOM_ASSET_CONNECTION_REFILL_BYTES_PER_SECOND, timeSource),
            outgoingChannels.fold(0) { mask, channel -> mask or outgoingChannelBit(channel) },
        )
        connection
    }

    fun current(playerId: UUID, connectionIdentity: Any): ConnectionKey? = synchronized(monitor) {
        entries[playerId]
            ?.takeIf { entry -> entry.belongsTo(connectionIdentity) }
            ?.connection
    }

    fun isActive(connection: ConnectionKey): Boolean = synchronized(monitor) {
        entries[connection.playerId]?.connection == connection
    }

    fun isActive(connection: ConnectionKey, connectionIdentity: Any): Boolean = synchronized(monitor) {
        entries[connection.playerId]
            ?.takeIf { entry -> entry.connection == connection }
            ?.belongsTo(connectionIdentity) == true
    }

    fun activeConnections(): List<ConnectionKey> = synchronized(monitor) {
        entries.values.mapNotNull { entry -> entry.connection.takeIf { entry.protocolActive } }
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
        if (!entry.supportsAllOutgoingChannels()) {
            entry.protocolActive = false
        }
        true
    }

    fun supportsOutgoingChannel(connection: ConnectionKey, channel: String): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        val bit = outgoingChannelBit(channel)
        bit != 0 && entry.outgoingChannelsMask and bit == bit
    }

    fun supportsAllOutgoingChannels(connection: ConnectionKey): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        entry.supportsAllOutgoingChannels()
    }

    fun activateProtocol(connection: ConnectionKey): Boolean = synchronized(monitor) {
        val entry = entries[connection.playerId]?.takeIf { active -> active.connection == connection } ?: return false
        if (!entry.supportsAllOutgoingChannels()) {
            return false
        }
        entry.protocolActive = true
        true
    }

    fun isProtocolActive(connection: ConnectionKey): Boolean = synchronized(monitor) {
        entries[connection.playerId]
            ?.takeIf { entry -> entry.connection == connection }
            ?.protocolActive == true
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
            if (!active.protocolActive) {
                return PaperClientHelloIngress.PROTOCOL_INACTIVE
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
            if (!active.protocolActive) {
                return PaperSelectionIngress.PROTOCOL_INACTIVE
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

    fun admitCustomSelection(
        connection: ConnectionKey,
        encodedByteCount: Int,
        decode: () -> CustomEmotionSelection,
    ): PaperCustomSelectionIngress {
        if (encodedByteCount !in 1..ProtocolV1Limits.CUSTOM_SELECT_BODY_BYTES) {
            return PaperCustomSelectionIngress.INVALID_SIZE
        }
        val permit = synchronized(monitor) {
            val active = entries[connection.playerId]
            if (active?.connection != connection) {
                return PaperCustomSelectionIngress.STALE_CONNECTION
            }
            if (!active.protocolActive) {
                return PaperCustomSelectionIngress.PROTOCOL_INACTIVE
            }
            if (!active.selectionGuard.tryAdmit()) {
                return PaperCustomSelectionIngress.RATE_LIMITED
            }
            val permit = when (val admission = globalSelections.tryAcquire()) {
                is GlobalSelectionIngressAdmission.Admitted -> SelectionPermit(active, admission.lease)
                GlobalSelectionIngressAdmission.OutstandingLimitReached,
                GlobalSelectionIngressAdmission.RateLimited,
                -> return PaperCustomSelectionIngress.RATE_LIMITED
            }
            if (
                !active.customSelectionBytes.tryConsume(encodedByteCount) ||
                !customAssetBytes.tryConsume(encodedByteCount)
            ) {
                permit.lease.release()
                return PaperCustomSelectionIngress.RATE_LIMITED
            }
            permit
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
                PaperCustomSelectionIngress.STALE_CONNECTION
            } else {
                PaperCustomSelectionIngress.Admitted(selection, permit.lease)
            }
        }
    }

    fun admitCustomAssetChunk(
        connection: ConnectionKey,
        encodedByteCount: Int,
        decode: () -> CustomEmojiAssetChunk,
    ): PaperCustomAssetChunkIngress {
        if (encodedByteCount !in 1..ProtocolV1Limits.CUSTOM_ASSET_CHUNK_BODY_BYTES) {
            return PaperCustomAssetChunkIngress.INVALID_SIZE
        }
        val entry = synchronized(monitor) {
            val active = entries[connection.playerId]
            if (active?.connection != connection) {
                return PaperCustomAssetChunkIngress.STALE_CONNECTION
            }
            if (!active.protocolActive) {
                return PaperCustomAssetChunkIngress.PROTOCOL_INACTIVE
            }
            if (
                !active.customAssetChunkBytes.tryConsume(encodedByteCount) ||
                !customAssetBytes.tryConsume(encodedByteCount)
            ) {
                return PaperCustomAssetChunkIngress.RATE_LIMITED
            }
            active
        }
        val chunk = decode()
        return synchronized(monitor) {
            if (entries[connection.playerId] !== entry) {
                PaperCustomAssetChunkIngress.STALE_CONNECTION
            } else {
                PaperCustomAssetChunkIngress.Admitted(chunk)
            }
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
        customAssetBytes.reset()
        cleared
    }

    private class Entry(
        val connection: ConnectionKey,
        connectionIdentity: Any,
        val helloRequests: TokenBucket,
        val helloGuard: ClientHelloIngressGuard,
        val selectionGuard: SelectionIngressGuard,
        val customSelectionBytes: TokenBucket,
        val customAssetChunkBytes: TokenBucket,
        var outgoingChannelsMask: Int,
    ) {
        private val connectionReference = WeakReference(connectionIdentity)
        var protocolActive = false

        fun belongsTo(connectionIdentity: Any): Boolean = connectionReference.get() === connectionIdentity

        fun supportsAllOutgoingChannels(): Boolean =
            outgoingChannelsMask and ALL_OUTGOING_CHANNELS_MASK == ALL_OUTGOING_CHANNELS_MASK
    }

    private data class SelectionPermit(
        val entry: Entry,
        val lease: GlobalSelectionIngressLease,
    )

    private companion object {
        const val HELLO_BURST_CAPACITY = 2
        const val HELLO_REFILL_TOKENS_PER_SECOND = 1
        const val CUSTOM_SELECTION_CONNECTION_BURST_BYTES = ProtocolV1Limits.CUSTOM_SELECT_BODY_BYTES
        const val CUSTOM_SELECTION_CONNECTION_REFILL_BYTES_PER_SECOND =
            (ProtocolV1Limits.CUSTOM_SELECT_BODY_BYTES + 2) / 3
        const val CUSTOM_ASSET_MAXIMUM_CHUNKS =
            (CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES + CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES - 1) /
                CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
        const val CUSTOM_ASSET_MAXIMUM_CHUNK_OVERHEAD_BYTES =
            ProtocolV1Limits.CUSTOM_ASSET_CHUNK_BODY_BYTES - CustomEmojiAssetChunker.MAXIMUM_CHUNK_DATA_BYTES
        const val CUSTOM_ASSET_CONNECTION_BURST_BYTES = CustomEmojiLosslessCodec.MAXIMUM_ENCODED_BYTES +
            CUSTOM_ASSET_MAXIMUM_CHUNKS * CUSTOM_ASSET_MAXIMUM_CHUNK_OVERHEAD_BYTES
        const val CUSTOM_ASSET_CONNECTION_REFILL_BYTES_PER_SECOND = 8 * 1_024
        const val CUSTOM_ASSET_GLOBAL_BURST_BYTES = 17 * 1_024 * 1_024
        const val CUSTOM_ASSET_GLOBAL_REFILL_BYTES_PER_SECOND = 8 * 1_024 * 1_024
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
