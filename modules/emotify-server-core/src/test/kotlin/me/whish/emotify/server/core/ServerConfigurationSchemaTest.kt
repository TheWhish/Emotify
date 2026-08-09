package me.whish.emotify.server.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

@Suppress("unused")
class ServerConfigurationSchemaTest : FunSpec({
    test("missing and explicit legacy versions require the only supported migration") {
        ServerConfigurationSchema.classify(null) shouldBe ServerConfigurationVersion.Legacy
        ServerConfigurationSchema.classify(0) shouldBe ServerConfigurationVersion.Legacy
    }

    test("current and future versions remain distinct") {
        ServerConfigurationSchema.classify(1) shouldBe ServerConfigurationVersion.Current
        ServerConfigurationSchema.classify(2) shouldBe ServerConfigurationVersion.Future(2)
        ServerConfigurationSchema.classify(Int.MAX_VALUE) shouldBe ServerConfigurationVersion.Future(Int.MAX_VALUE)
    }

    test("negative versions are rejected") {
        shouldThrow<IllegalArgumentException> {
            ServerConfigurationSchema.classify(-1)
        }
    }
})
