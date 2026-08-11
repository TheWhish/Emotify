package me.whish.emotify.client.state

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiPixels
import me.whish.emotify.domain.FakeMonotonicTimeSource
import kotlin.time.Duration.Companion.seconds
import me.whish.emotify.domain.SelectionRejectionReason

@Suppress("unused")
class ClientCustomEmojiTransferStateTest : FunSpec({
    val asset = CustomEmojiAsset.create(CustomEmojiPixels.of(IntArray(64) { it }))
    val descriptor = CustomEmojiDescriptor.create("Танец", asset.id)

    test("upload tracker sends content once per connection and can recover from server cache loss") {
        val tracker = ClientCustomEmojiUploadTracker()
        tracker.begin(1L)

        tracker.prepare(1L, asset, descriptor)?.asset shouldBe asset
        tracker.markUploaded(1L, asset.id) shouldBe true
        tracker.prepare(1L, asset, descriptor)?.asset.shouldBeNull()
        tracker.forget(1L, asset.id) shouldBe true
        tracker.prepare(1L, asset, descriptor)?.asset shouldBe asset

        tracker.disconnect(1L)
        tracker.prepare(1L, asset, descriptor).shouldBeNull()
    }

    test("failed lossless selection publication rolls back its provisional upload marker") {
        val tracker = ClientCustomEmojiUploadTracker()
        tracker.begin(1L)

        shouldThrow<IllegalStateException> {
            tracker.commitProvisionalUpload(1L, asset.id) {
                error("selection send failed")
            }
        }

        tracker.requiresUpload(1L, asset.id) shouldBe true
        tracker.commitProvisionalUpload(1L, asset.id) { "sent" } shouldBe "sent"
        tracker.requiresUpload(1L, asset.id) shouldBe false
    }

    test("asset ingress guard is connection scoped and bounded") {
        val time = FakeMonotonicTimeSource()
        val guard = ClientCustomEmojiAssetIngressGuard(time)
        guard.begin(1L)

        repeat(ClientCustomEmojiAssetIngressGuard.ASSET_BURST_CAPACITY) {
            guard.tryAdmit(1L) shouldBe true
        }
        guard.tryAdmit(1L) shouldBe false
        guard.tryAdmit(2L) shouldBe false
        time.advanceBy(1.seconds)
        guard.tryAdmit(1L) shouldBe true
    }

    test("definitive server rejections suppress repeated uploads until policy refresh") {
        val tracker = ClientCustomEmojiUploadTracker()
        tracker.begin(1L)

        tracker.reject(1L, asset.id, SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE) shouldBe true
        tracker.rejection(1L, asset.id) shouldBe SelectionRejectionReason.CUSTOM_EMOJI_TOO_LARGE
        tracker.clearRejections(1L) shouldBe true
        tracker.rejection(1L, asset.id).shouldBeNull()

        tracker.rejectAll(1L, SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED) shouldBe true
        tracker.rejection(1L, asset.id) shouldBe SelectionRejectionReason.CUSTOM_EMOJIS_DISABLED
        tracker.begin(2L)
        tracker.rejection(2L, asset.id).shouldBeNull()
    }
})
