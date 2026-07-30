package me.whish.emotify.fabric

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import me.whish.emotify.fabric.client.EmotifyFabricClient

class FabricModuleSmokeTest : FunSpec({
    test("uses the canonical mod id") {
        EmotifyFabric.ID shouldBe "emotify"
    }

    test("Fabric entrypoints expose public no argument constructors") {
        listOf(EmotifyFabric::class.java, EmotifyFabricClient::class.java).forEach { entrypoint ->
            val constructor = entrypoint.getDeclaredConstructor()

            Modifier.isPublic(entrypoint.modifiers) shouldBe true
            Modifier.isPublic(constructor.modifiers) shouldBe true
        }
    }
})
