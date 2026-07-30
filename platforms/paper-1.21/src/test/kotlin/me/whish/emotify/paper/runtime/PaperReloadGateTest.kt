package me.whish.emotify.paper.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import me.whish.emotify.domain.FakeMonotonicTimeSource

@Suppress("unused")
class PaperReloadGateTest : FunSpec({
    test("reload is single flight and paced below the client policy refresh limit") {
        val time = FakeMonotonicTimeSource()
        val gate = PaperReloadGate(time)
        val ticket = gate.tryBegin().shouldBeInstanceOf<PaperReloadAdmission.Admitted>().ticket

        gate.tryBegin() shouldBe PaperReloadAdmission.Pending
        gate.complete(ticket) shouldBe true
        gate.tryBegin() shouldBe PaperReloadAdmission.RateLimited
        time.advanceBy(1.seconds)
        gate.tryBegin().shouldBeInstanceOf<PaperReloadAdmission.Admitted>()
    }

    test("disable invalidates an asynchronous completion ticket") {
        val gate = PaperReloadGate(FakeMonotonicTimeSource())
        val ticket = gate.tryBegin().shouldBeInstanceOf<PaperReloadAdmission.Admitted>().ticket

        gate.invalidate()

        gate.complete(ticket) shouldBe false
        gate.tryBegin().shouldBeInstanceOf<PaperReloadAdmission.Admitted>()
    }
})
