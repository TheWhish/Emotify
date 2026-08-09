package me.whish.emotify.client.custom

import java.util.concurrent.atomic.AtomicReference
import me.whish.emotify.domain.CustomEmojiId

class CustomEmojiCopyRequestGate {
    private val activeOrigin = AtomicReference<CustomEmojiId?>()

    fun tryBegin(originId: CustomEmojiId): Boolean = activeOrigin.compareAndSet(null, originId)

    fun complete(originId: CustomEmojiId): Boolean = activeOrigin.compareAndSet(originId, null)
}
