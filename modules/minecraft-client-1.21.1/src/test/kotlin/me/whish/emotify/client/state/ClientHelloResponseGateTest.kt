package me.whish.emotify.client.state

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

@Suppress("unused")
class ClientHelloResponseGateTest : FunSpec({
    test("client hello is sent once only after a server hello for the active connection") {
        val gate = ClientHelloResponseGate()

        gate.begin(7L)

        gate.hasResponded(7L).shouldBeFalse()
        gate.tryRespond(6L).shouldBeFalse()
        gate.tryRespond(7L).shouldBeTrue()
        gate.hasResponded(7L).shouldBeTrue()
        gate.tryRespond(7L).shouldBeFalse()
    }

    test("reconnect replaces the previous response state") {
        val gate = ClientHelloResponseGate()

        gate.begin(11L)
        gate.tryRespond(11L).shouldBeTrue()
        gate.begin(12L)

        gate.hasResponded(11L).shouldBeFalse()
        gate.hasResponded(12L).shouldBeFalse()
        gate.tryRespond(11L).shouldBeFalse()
        gate.tryRespond(12L).shouldBeTrue()
    }

    test("disconnect invalidates the active response state") {
        val gate = ClientHelloResponseGate()

        gate.begin(19L)
        gate.tryRespond(19L).shouldBeTrue()
        gate.disconnect(19L)

        gate.hasResponded(19L).shouldBeFalse()
        gate.tryRespond(19L).shouldBeFalse()
    }

    test("failed send releases the active response reservation") {
        val gate = ClientHelloResponseGate()

        gate.begin(23L)
        gate.tryRespond(23L).shouldBeTrue()
        gate.cancelResponse(22L)
        gate.hasResponded(23L).shouldBeTrue()
        gate.cancelResponse(23L)

        gate.hasResponded(23L).shouldBeFalse()
        gate.tryRespond(23L).shouldBeTrue()
    }
})
