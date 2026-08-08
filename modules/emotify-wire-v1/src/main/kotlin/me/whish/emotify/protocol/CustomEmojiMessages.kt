package me.whish.emotify.protocol

import java.util.UUID
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId

data class CustomEmotionSelection(
    val customEmojiId: CustomEmojiId,
    val asset: CustomEmojiAsset?,
) {
    init {
        require(asset == null || asset.id == customEmojiId) {
            "Custom selection asset must match its content ID"
        }
    }
}

data class CustomEmojiTransfer(
    val asset: CustomEmojiAsset,
)

class CustomEmojiAssetChunk private constructor(
    val customEmojiId: CustomEmojiId,
    val totalBytes: Int,
    val index: Int,
    val count: Int,
    data: ByteArray,
    copyData: Boolean,
) {
    constructor(
        customEmojiId: CustomEmojiId,
        totalBytes: Int,
        index: Int,
        count: Int,
        data: ByteArray,
    ) : this(customEmojiId, totalBytes, index, count, data, true)

    private val bytes = if (copyData) data.copyOf() else data

    val dataLength: Int
        get() = bytes.size

    init {
        require(totalBytes > 0) { "Custom emoji transfer size must be positive: $totalBytes" }
        require(count > 0) { "Custom emoji chunk count must be positive: $count" }
        require(index in 0 until count) { "Custom emoji chunk index is outside the transfer: $index/$count" }
        require(bytes.isNotEmpty()) { "Custom emoji chunk cannot be empty" }
        require(bytes.size <= totalBytes) { "Custom emoji chunk exceeds its complete transfer size" }
    }

    internal fun writeData(writer: me.whish.emotify.wire.v1.WireWriter) {
        writer.writeBytes(bytes)
    }

    internal fun copyDataTo(destination: ByteArray, destinationOffset: Int) {
        bytes.copyInto(destination, destinationOffset)
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is CustomEmojiAssetChunk &&
            customEmojiId == other.customEmojiId &&
            totalBytes == other.totalBytes &&
            index == other.index &&
            count == other.count &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = customEmojiId.hashCode()
        result = 31 * result + totalBytes
        result = 31 * result + index
        result = 31 * result + count
        return 31 * result + bytes.contentHashCode()
    }

    override fun toString(): String =
        "CustomEmojiAssetChunk(customEmojiId=$customEmojiId, totalBytes=$totalBytes, index=$index, count=$count, dataLength=${bytes.size})"

    companion object {
        internal fun takeOwnership(
            customEmojiId: CustomEmojiId,
            totalBytes: Int,
            index: Int,
            count: Int,
            data: ByteArray,
        ): CustomEmojiAssetChunk = CustomEmojiAssetChunk(customEmojiId, totalBytes, index, count, data, false)
    }
}

data class CustomEmotionPlay(
    val entityId: RuntimeEntityId,
    val sourceUuid: UUID,
    val sequence: EventSequence,
    val customEmojiId: CustomEmojiId,
) {
    fun asEmotionPlay(): EmotionPlay = EmotionPlay(
        entityId,
        sourceUuid,
        sequence,
        customEmojiId.emotionId,
    )
}
