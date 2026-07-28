package me.whish.emotify.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

class SelectionRejectionReasonTest : FunSpec({
    test("rejection reasons keep their stable domain order") {
        SelectionRejectionReason.entries shouldContainExactly listOf(
            SelectionRejectionReason.COOLDOWN,
            SelectionRejectionReason.SERVER_DISABLED,
            SelectionRejectionReason.EMOTION_DISABLED,
            SelectionRejectionReason.PLAYER_STATE,
            SelectionRejectionReason.SERVER_BUSY,
        )
    }
})
