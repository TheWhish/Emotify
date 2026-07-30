package me.whish.emotify.domain

fun interface MonotonicTimeSource {
    fun nowNanos(): Long
}

data object SystemMonotonicTimeSource : MonotonicTimeSource {
    override fun nowNanos(): Long = System.nanoTime()
}
