package me.whish.emotify.server

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

class DimensionOrdinalRegistry {
    private val ordinals = Object2IntOpenHashMap<ResourceKey<Level>>().apply {
        defaultReturnValue(0)
    }
    private var nextOrdinal = 1

    fun resolve(dimension: ResourceKey<Level>): Int = ordinals.getInt(dimension).takeIf { it != 0 } ?: run {
        val ordinal = nextOrdinal
        nextOrdinal = Math.incrementExact(nextOrdinal)
        ordinals.put(dimension, ordinal)
        ordinal
    }

    fun clear() {
        ordinals.clear()
        nextOrdinal = 1
    }
}
