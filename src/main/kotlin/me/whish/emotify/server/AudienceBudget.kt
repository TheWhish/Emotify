package me.whish.emotify.server

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import me.whish.emotify.domain.MonotonicTimeSource
import me.whish.emotify.domain.SystemMonotonicTimeSource
import me.whish.emotify.domain.TokenBucket

enum class AudienceReservation {
    RESERVED,
    GLOBAL_BUSY,
    REGION_BUSY,
}

class AudienceBudget(
    globalCapacity: Int = 512,
    globalRefillTokensPerSecond: Int = 256,
    private val regionCapacity: Int = 32,
    private val regionRefillTokensPerSecond: Int = 16,
    regionIdleTtl: Duration = 60.seconds,
    sweepInterval: Duration = 10.seconds,
    private val maxRegions: Int = 4_096,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private val global = TokenBucket(globalCapacity, globalRefillTokensPerSecond, timeSource)
    private val regionIdleTtlNanos = regionIdleTtl.inWholeNanoseconds
    private val sweepIntervalNanos = sweepInterval.inWholeNanoseconds
    private val regions = Int2ObjectOpenHashMap<Long2ObjectOpenHashMap<RegionBucket>>()
    private var regionCount = 0
    private var lastSweepNanos = timeSource.nowNanos()

    init {
        require(regionCapacity > 0) { "Region capacity must be positive: $regionCapacity" }
        require(regionRefillTokensPerSecond > 0) { "Region refill rate must be positive: $regionRefillTokensPerSecond" }
        require(regionIdleTtlNanos > 0L) { "Region idle TTL must be positive: $regionIdleTtl" }
        require(sweepIntervalNanos > 0L) { "Region sweep interval must be positive: $sweepInterval" }
        require(maxRegions > 0) { "Region budget limit must be positive: $maxRegions" }
    }

    fun tryReserve(dimensionId: Int, regionKey: Long): AudienceReservation {
        val nowNanos = timeSource.nowNanos()
        sweepExpired(nowNanos)
        if (!global.tryConsume()) {
            return AudienceReservation.GLOBAL_BUSY
        }

        val region = findOrCreateRegion(dimensionId, regionKey, nowNanos)
        if (region == null) {
            global.refundOne()
            return AudienceReservation.REGION_BUSY
        }
        region.lastTouchedNanos = nowNanos
        if (!region.bucket.tryConsume()) {
            global.refundOne()
            return AudienceReservation.REGION_BUSY
        }
        return AudienceReservation.RESERVED
    }

    fun refund(dimensionId: Int, regionKey: Long) {
        val region = regions[dimensionId]?.get(regionKey)
        checkNotNull(region) { "Cannot refund missing audience region $dimensionId:$regionKey" }
        region.bucket.refundOne()
        global.refundOne()
    }

    fun clear() {
        global.reset()
        regions.clear()
        regionCount = 0
        lastSweepNanos = timeSource.nowNanos()
    }

    private fun findOrCreateRegion(dimensionId: Int, regionKey: Long, nowNanos: Long): RegionBucket? {
        val dimensionRegions = regions[dimensionId]
        val existing = dimensionRegions?.get(regionKey)
        if (existing != null) {
            return existing
        }
        if (regionCount >= maxRegions) {
            return null
        }

        val targetRegions = dimensionRegions ?: Long2ObjectOpenHashMap<RegionBucket>().also {
            regions[dimensionId] = it
        }
        return RegionBucket(
            TokenBucket(regionCapacity, regionRefillTokensPerSecond, timeSource),
            nowNanos,
        ).also {
            targetRegions[regionKey] = it
            regionCount += 1
        }
    }

    private fun sweepExpired(nowNanos: Long) {
        val elapsedSinceSweep = nowNanos - lastSweepNanos
        check(elapsedSinceSweep >= 0L) { "Monotonic time source moved backwards" }
        if (elapsedSinceSweep < sweepIntervalNanos) {
            return
        }
        lastSweepNanos = nowNanos

        val dimensionIterator = regions.int2ObjectEntrySet().fastIterator()
        while (dimensionIterator.hasNext()) {
            val dimensionEntry = dimensionIterator.next()
            val regionIterator = dimensionEntry.value.long2ObjectEntrySet().fastIterator()
            while (regionIterator.hasNext()) {
                val regionEntry = regionIterator.next()
                if (nowNanos - regionEntry.value.lastTouchedNanos > regionIdleTtlNanos) {
                    regionIterator.remove()
                    regionCount -= 1
                }
            }
            if (dimensionEntry.value.isEmpty()) {
                dimensionIterator.remove()
            }
        }
    }

    private class RegionBucket(
        val bucket: TokenBucket,
        var lastTouchedNanos: Long,
    )
}
