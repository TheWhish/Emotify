package me.whish.emotify.fabric.network

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.whish.emotify.fabric.network.payload.FabricEmotionPlayPayload
import me.whish.emotify.fabric.network.payload.FabricSelectionRejectedPayload
import me.whish.emotify.fabric.network.payload.FabricServerHelloPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmojiAssetPayload
import me.whish.emotify.fabric.network.payload.FabricCustomEmotionPlayPayload
import net.minecraft.resources.ResourceLocation

@Suppress("unused")
class FabricClientboundChannelSetTest : FunSpec({
    test("NeoForge configuration advertisement enables Fabric play payloads") {
        val configurationMask = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            requiredChannels,
        )

        FabricClientboundChannelSet.supportsProtocol(emptySet(), configurationMask) shouldBe true
    }

    test("configuration advertisements accumulate without allocating connection state") {
        val first = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            listOf(FabricServerHelloPayload.TYPE.id()),
        )
        val complete = FabricClientboundChannelSet.register(
            first,
            listOf(
                FabricEmotionPlayPayload.TYPE.id(),
                FabricSelectionRejectedPayload.TYPE.id(),
            ),
        )

        FabricClientboundChannelSet.supportsProtocol(emptySet(), first) shouldBe false
        FabricClientboundChannelSet.supportsProtocol(emptySet(), complete) shouldBe true
    }

    test("play and configuration advertisements form one directional capability set") {
        val configurationMask = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            listOf(FabricSelectionRejectedPayload.TYPE.id()),
        )
        val playChannels = setOf(
            FabricServerHelloPayload.TYPE.id(),
            FabricEmotionPlayPayload.TYPE.id(),
        )

        FabricClientboundChannelSet.supportsProtocol(playChannels, configurationMask) shouldBe true
    }

    test("unregister removes only the declared channels") {
        val complete = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            requiredChannels,
        )
        val reduced = FabricClientboundChannelSet.unregister(
            complete,
            listOf(FabricEmotionPlayPayload.TYPE.id()),
        )

        FabricClientboundChannelSet.supportsProtocol(emptySet(), reduced) shouldBe false
        FabricClientboundChannelSet.supports(
            FabricServerHelloPayload.TYPE.id(),
            emptySet(),
            reduced,
        ) shouldBe true
        FabricClientboundChannelSet.supports(
            FabricEmotionPlayPayload.TYPE.id(),
            emptySet(),
            reduced,
        ) shouldBe false
    }

    test("unknown channels cannot authorize Emotify payloads") {
        val configurationMask = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            listOf(ResourceLocation.fromNamespaceAndPath("example", "unknown")),
        )

        configurationMask shouldBe FabricClientboundChannelSet.EMPTY
        FabricClientboundChannelSet.supportsProtocol(emptySet(), configurationMask) shouldBe false
    }

    test("custom emoji channels are tracked independently from base channels") {
        val customMask = FabricClientboundChannelSet.register(
            FabricClientboundChannelSet.EMPTY,
            listOf(FabricCustomEmojiAssetPayload.TYPE.id(), FabricCustomEmotionPlayPayload.TYPE.id()),
        )

        FabricClientboundChannelSet.supportsCustomEmojis(emptySet(), customMask) shouldBe true
        FabricClientboundChannelSet.supportsProtocol(emptySet(), customMask) shouldBe false
    }
})

private val requiredChannels = listOf(
    FabricServerHelloPayload.TYPE.id(),
    FabricEmotionPlayPayload.TYPE.id(),
    FabricSelectionRejectedPayload.TYPE.id(),
)
