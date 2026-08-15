package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.input.MouseButtonEvent

@Suppress("unused")
class EmotifySettingsBackgroundScreenTest : FunSpec({
    test("all settings pages use the shared background lifecycle") {
        val backgroundOwner = EmotifySettingsBackgroundScreen::class.java
        val ignoredPlayersScreen = Class.forName("me.whish.emotify.client.IgnoredPlayersScreen")

        EmotifySettingsScreen::class.java.superclass shouldBe backgroundOwner
        ignoredPlayersScreen.superclass shouldBe backgroundOwner
    }

    test("settings pages cannot replace the shared background lifecycle") {
        val renderBackground = EmotifySettingsBackgroundScreen::class.java.getDeclaredMethod(
            "renderBackground",
            GuiGraphics::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
        )

        Modifier.isFinal(renderBackground.modifiers) shouldBe true
        EmotifySettingsScreen::class.java.declaredMethods.none { method ->
            method.name == "renderBackground"
        } shouldBe true
        Class.forName("me.whish.emotify.client.IgnoredPlayersScreen").declaredMethods.none { method ->
            method.name == "renderBackground"
        } shouldBe true
    }

    test("settings pages share focus and pause behavior") {
        val backgroundOwner = EmotifySettingsBackgroundScreen::class.java
        val ignoredPlayersScreen = Class.forName("me.whish.emotify.client.IgnoredPlayersScreen")
        val mouseClicked = backgroundOwner.getDeclaredMethod(
            "mouseClicked",
            MouseButtonEvent::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val isPauseScreen = backgroundOwner.getDeclaredMethod("isPauseScreen")

        Modifier.isFinal(mouseClicked.modifiers) shouldBe true
        Modifier.isFinal(isPauseScreen.modifiers) shouldBe true
        EmotifySettingsScreen::class.java.declaredMethods.none { method ->
            method.name == "mouseClicked" || method.name == "isPauseScreen"
        } shouldBe true
        ignoredPlayersScreen.declaredMethods.none { method ->
            method.name == "mouseClicked" || method.name == "isPauseScreen"
        } shouldBe true
    }
})
