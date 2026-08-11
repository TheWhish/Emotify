package me.whish.emotify.paper.runtime

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.util.UUID

class PaperDimensionOrdinalRegistry {
    private val ordinals = Object2IntOpenHashMap<UUID>().apply {
        defaultReturnValue(0)
    }
    private var nextOrdinal = 1

    fun resolve(worldId: UUID): Int = ordinals.getInt(worldId).takeIf { ordinal -> ordinal != 0 } ?: run {
        val ordinal = nextOrdinal
        nextOrdinal = Math.incrementExact(nextOrdinal)
        ordinals.put(worldId, ordinal)
        ordinal
    }

    fun remove(worldId: UUID): Boolean = ordinals.removeInt(worldId) != 0

    fun clear() {
        ordinals.clear()
        nextOrdinal = 1
    }
}

object PaperRegionKey {
    fun fromPosition(x: Double, z: Double): Long {
        val chunkX = Math.floor(x).toInt() shr CHUNK_SHIFT
        val chunkZ = Math.floor(z).toInt() shr CHUNK_SHIFT
        return pack(chunkX, chunkZ)
    }

    private fun pack(x: Int, z: Int): Long =
        (x.toLong() and COORDINATE_MASK) or ((z.toLong() and COORDINATE_MASK) shl Integer.SIZE)

    private const val CHUNK_SHIFT = 4
    private const val COORDINATE_MASK = 0xFFFF_FFFFL
}
