package me.whish.emotify.client.custom

import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiId

enum class CustomEmojiLibraryAdmission {
    ACCEPTED,
    DUPLICATE,
    CAPACITY_REACHED,
}

class CustomEmojiLibraryBudget(
    private val maximumRetainedBytes: Long = DEFAULT_MAXIMUM_RETAINED_BYTES,
) {
    private val retainedIds = HashSet<CustomEmojiId>()
    private var retainedBytes = 0L

    init {
        require(maximumRetainedBytes >= MAXIMUM_SINGLE_ASSET_BYTES) {
            "Custom emoji library budget must fit one maximum asset: $maximumRetainedBytes"
        }
    }

    fun admit(id: CustomEmojiId, retainedByteLength: Long): CustomEmojiLibraryAdmission {
        require(retainedByteLength in 1..MAXIMUM_SINGLE_ASSET_BYTES) {
            "Custom emoji retained size is outside local limits: $retainedByteLength"
        }
        if (id in retainedIds) {
            return CustomEmojiLibraryAdmission.DUPLICATE
        }
        if (retainedByteLength > maximumRetainedBytes - retainedBytes) {
            return CustomEmojiLibraryAdmission.CAPACITY_REACHED
        }
        retainedIds += id
        retainedBytes += retainedByteLength
        return CustomEmojiLibraryAdmission.ACCEPTED
    }

    companion object {
        const val DEFAULT_MAXIMUM_RETAINED_BYTES = 32L * 1_024 * 1_024
        const val MAXIMUM_SINGLE_ASSET_BYTES =
            CustomEmojiAsset.MAXIMUM_RAW_BYTE_LENGTH.toLong() * 2L + 512L * 1_024L
    }
}
