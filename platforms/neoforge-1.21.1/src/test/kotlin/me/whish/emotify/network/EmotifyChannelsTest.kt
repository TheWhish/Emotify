package me.whish.emotify.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.network.payload.ClientHelloPayload
import me.whish.emotify.network.payload.EmotionPlayPayload
import me.whish.emotify.network.payload.EmotionSelectionPayload
import me.whish.emotify.network.payload.SelectionRejectedPayload
import me.whish.emotify.network.payload.ServerHelloPayload
import me.whish.emotify.network.payload.CustomEmotionSelectionPayload
import me.whish.emotify.network.payload.CustomEmojiAssetPayload
import me.whish.emotify.network.payload.CustomEmotionPlayPayload
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

@Suppress("unused")
class EmotifyChannelsTest : FunSpec({
    test("NeoForge client accepts a server that advertises only serverbound channels") {
        val advertised = setOf(
            ClientHelloPayload.TYPE,
            EmotionSelectionPayload.TYPE,
        )

        EmotifyChannels.serverCanReceiveClientPayloads(advertised::contains) shouldBe true
        EmotifyChannels.clientCanReceiveServerPayloads(advertised::contains) shouldBe false
    }

    test("NeoForge server accepts a client that advertises only clientbound channels") {
        val advertised = setOf(
            ServerHelloPayload.TYPE,
            EmotionPlayPayload.TYPE,
            SelectionRejectedPayload.TYPE,
        )

        EmotifyChannels.clientCanReceiveServerPayloads(advertised::contains) shouldBe true
        EmotifyChannels.serverCanReceiveClientPayloads(advertised::contains) shouldBe false
    }

    test("client to server support requires every serverbound channel") {
        val required = listOf(
            ClientHelloPayload.TYPE,
            EmotionSelectionPayload.TYPE,
        )

        required.forEach { missing ->
            EmotifyChannels.serverCanReceiveClientPayloads(required.without(missing)::contains) shouldBe false
        }
    }

    test("server to client support requires every clientbound channel") {
        val required = listOf(
            ServerHelloPayload.TYPE,
            EmotionPlayPayload.TYPE,
            SelectionRejectedPayload.TYPE,
        )

        required.forEach { missing ->
            EmotifyChannels.clientCanReceiveServerPayloads(required.without(missing)::contains) shouldBe false
        }
    }

    test("custom emoji capability is negotiated independently from the base protocol") {
        EmotifyChannels.serverCanReceiveCustomSelections(setOf(CustomEmotionSelectionPayload.TYPE)::contains) shouldBe true
        EmotifyChannels.clientCanReceiveCustomEmojis(
            setOf(CustomEmojiAssetPayload.TYPE, CustomEmotionPlayPayload.TYPE)::contains,
        ) shouldBe true
        EmotifyChannels.clientCanReceiveCustomEmojis(setOf(CustomEmotionPlayPayload.TYPE)::contains) shouldBe false
    }
})

private fun List<CustomPacketPayload.Type<*>>.without(
    excluded: CustomPacketPayload.Type<*>,
): Set<CustomPacketPayload.Type<*>> = filterTo(linkedSetOf()) { type -> type !== excluded }
