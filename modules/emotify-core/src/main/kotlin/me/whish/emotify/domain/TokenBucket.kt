package me.whish.emotify.domain

class TokenBucket(
    capacity: Int,
    refillTokensPerSecond: Int,
    private val timeSource: MonotonicTimeSource = SystemMonotonicTimeSource,
) {
    private var capacityUnits: Long
    private var refillTokensPerSecond: Int
    private var availableUnits: Long
    private var lastRefillNanos = timeSource.nowNanos()

    init {
        validate(capacity, refillTokensPerSecond)
        capacityUnits = capacity * UNITS_PER_TOKEN
        this.refillTokensPerSecond = refillTokensPerSecond
        availableUnits = capacityUnits
    }

    fun tryConsume(): Boolean {
        return tryConsume(1)
    }

    fun tryConsume(tokens: Int): Boolean = tryConsumeRetaining(tokens, 0)

    fun tryConsumeRetaining(minimumWholeTokens: Int): Boolean {
        return tryConsumeRetaining(1, minimumWholeTokens)
    }

    fun tryConsumeRetaining(tokens: Int, minimumWholeTokens: Int): Boolean {
        require(tokens > 0) { "Consumed token count must be positive: $tokens" }
        require(minimumWholeTokens >= 0) { "Retained token count must not be negative: $minimumWholeTokens" }
        val consumedUnits = tokens.toLong() * UNITS_PER_TOKEN
        val retainedUnits = minimumWholeTokens.toLong() * UNITS_PER_TOKEN
        require(retainedUnits <= capacityUnits) { "Retained token count exceeds bucket capacity: $minimumWholeTokens" }
        refill()
        if (availableUnits - retainedUnits < consumedUnits) {
            return false
        }

        availableUnits -= consumedUnits
        return true
    }

    fun refundOne() {
        refund(1)
    }

    fun refund(tokens: Int) {
        require(tokens > 0) { "Refunded token count must be positive: $tokens" }
        refill()
        val refundedUnits = tokens.toLong() * UNITS_PER_TOKEN
        availableUnits = if (refundedUnits >= capacityUnits - availableUnits) {
            capacityUnits
        } else {
            availableUnits + refundedUnits
        }
    }

    fun reset() {
        availableUnits = capacityUnits
        lastRefillNanos = timeSource.nowNanos()
    }

    fun reconfigure(capacity: Int, refillTokensPerSecond: Int) {
        validate(capacity, refillTokensPerSecond)
        refill()
        capacityUnits = capacity * UNITS_PER_TOKEN
        availableUnits = availableUnits.coerceAtMost(capacityUnits)
        this.refillTokensPerSecond = refillTokensPerSecond
    }

    fun availableWholeTokens(): Int {
        refill()
        return (availableUnits / UNITS_PER_TOKEN).toInt()
    }

    private fun refill() {
        val nowNanos = timeSource.nowNanos()
        val elapsedNanos = nowNanos - lastRefillNanos
        check(elapsedNanos >= 0L) { "Monotonic time source moved backwards" }
        lastRefillNanos = nowNanos

        val missingUnits = capacityUnits - availableUnits
        if (elapsedNanos == 0L || missingUnits == 0L) {
            return
        }

        val nanosToCapacity = (missingUnits - 1L) / refillTokensPerSecond + 1L
        availableUnits = if (elapsedNanos >= nanosToCapacity) {
            capacityUnits
        } else {
            availableUnits + elapsedNanos * refillTokensPerSecond
        }
    }

    private fun validate(capacity: Int, refillTokensPerSecond: Int) {
        require(capacity > 0) { "Token bucket capacity must be positive: $capacity" }
        require(capacity.toLong() <= Long.MAX_VALUE / UNITS_PER_TOKEN) {
            "Token bucket capacity is too large: $capacity"
        }
        require(refillTokensPerSecond > 0) { "Token refill rate must be positive: $refillTokensPerSecond" }
    }

    companion object {
        private const val UNITS_PER_TOKEN = 1_000_000_000L
    }
}
