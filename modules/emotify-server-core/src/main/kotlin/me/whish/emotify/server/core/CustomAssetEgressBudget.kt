package me.whish.emotify.server.core

import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket
import me.whish.emotify.wire.v1.CustomEmojiAssetChunker

class CustomAssetEgressBudget(
    burstBytes: Int = DEFAULT_BURST_BYTES,
    refillBytesPerSecond: Int = DEFAULT_REFILL_BYTES_PER_SECOND,
    timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val bytes = TokenBucket(burstBytes, refillBytesPerSecond, timeSource)

    init {
        require(burstBytes >= CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES) {
            "Custom asset egress burst must fit one maximum asset: $burstBytes"
        }
    }

    fun tryReserve(byteCount: Int): Boolean {
        require(byteCount in 1..CustomEmojiAssetChunker.MAXIMUM_WIRE_BYTES) {
            "Custom asset egress size is outside protocol limits: $byteCount"
        }
        return bytes.tryConsume(byteCount)
    }

    fun reset() {
        bytes.reset()
    }

    companion object {
        const val DEFAULT_BURST_BYTES = 16 * 1_024 * 1_024
        const val DEFAULT_REFILL_BYTES_PER_SECOND = 8 * 1_024 * 1_024
    }
}
