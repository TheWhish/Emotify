package me.whish.emotify.client

import java.util.concurrent.CompletableFuture
import me.whish.emotify.client.custom.CustomEmojiCopyRequestGate
import me.whish.emotify.client.custom.beginCustomEmojiCopy
import me.whish.emotify.client.interaction.CustomEmotionCopyHitArea
import me.whish.emotify.client.interaction.EmotionBillboardHitDetector
import me.whish.emotify.client.interaction.EmotionInteractionRay
import me.whish.emotify.client.interaction.InteractionVector3
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import me.whish.emotify.domain.CustomEmojiId
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult
import org.joml.Vector3f
import org.slf4j.LoggerFactory

object CustomEmojiCopyController {
    private val logger = LoggerFactory.getLogger("Emotify/CustomEmojiCopy")
    private val requests = CustomEmojiCopyRequestGate()

    @JvmStatic
    fun intercept(minecraft: Minecraft): Boolean {
        if (minecraft.screen != null) {
            return false
        }
        val localPlayer = minecraft.player ?: return false
        if (!localPlayer.isShiftKeyDown) {
            return false
        }
        val target = findTarget(minecraft) ?: return false
        return save(minecraft, target)
    }

    private fun findTarget(minecraft: Minecraft): CustomEmojiCopyTarget? {
        val level = minecraft.level ?: return null
        val localPlayer = minecraft.player ?: return null
        val cameraEntity = minecraft.cameraEntity ?: return null
        val partialTick = minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)
        val maximumDistance = localPlayer.blockInteractionRange()
        val rayOrigin = cameraEntity.getEyePosition(partialTick)
        val rayDirection = cameraEntity.getViewVector(partialTick)
        val blockHit = cameraEntity.pick(maximumDistance, partialTick, false)
        val occlusionDistance = if (blockHit.type == HitResult.Type.MISS) {
            maximumDistance
        } else {
            blockHit.location.distanceTo(rayOrigin)
        }
        val ray = EmotionInteractionRay(
            InteractionVector3(rayOrigin.x, rayOrigin.y, rayOrigin.z),
            InteractionVector3(rayDirection.x, rayDirection.y, rayDirection.z),
        )
        val orientation = minecraft.entityRenderDispatcher.cameraOrientation()
        val right = Vector3f(1.0f, 0.0f, 0.0f).rotate(orientation).toInteractionVector()
        val up = Vector3f(0.0f, 1.0f, 0.0f).rotate(orientation).toInteractionVector()
        var nearestDistance = Double.POSITIVE_INFINITY
        var nearestTarget: CustomEmojiCopyTarget? = null

        level.players().forEach { player ->
            if (player === localPlayer) {
                return@forEach
            }
            val active = ClientHandshakeController.renderableEmotionFor(player) ?: return@forEach
            val descriptor = active.customDescriptor ?: return@forEach
            val asset = RemoteCustomEmojiRegistry.asset(active.emotionId)
                ?: CustomEmojiRegistry.asset(active.emotionId)
                ?: return@forEach
            val playerPosition = player.getPosition(partialTick)
            val center = InteractionVector3(
                playerPosition.x,
                playerPosition.y + EmotionBillboardPlacement.localY(player, 0.0, partialTick),
                playerPosition.z,
            )
            val distance = EmotionBillboardHitDetector.intersectionDistance(
                ray,
                CustomEmotionCopyHitArea.create(center, right, up),
                maximumDistance,
                occlusionDistance,
            )
            if (distance != null && distance < nearestDistance) {
                nearestDistance = distance
                nearestTarget = CustomEmojiCopyTarget(asset, descriptor)
            }
        }
        return nearestTarget
    }

    private fun save(minecraft: Minecraft, target: CustomEmojiCopyTarget): Boolean {
        val export = try {
            beginCustomEmojiCopy(
                requests,
                target.descriptor.originId,
                CustomEmojiRegistry.containsOrigin(target.descriptor.originId),
            ) {
                CompletableFuture.supplyAsync({
                    CustomEmojiAssetExporter.export(
                        CustomEmojiRegistry.directory(minecraft),
                        target.asset,
                        target.descriptor,
                    )
                }, Util.ioPool())
            } ?: return false
        } catch (failure: RuntimeException) {
            logger.error("Failed to schedule custom emoji copy {}", target.asset.id, failure)
            return false
        }
        export.whenComplete { result, failure ->
            if (failure != null) {
                release(target.descriptor.originId)
                logger.error("Failed to save shared custom emoji {}", target.asset.id, failure)
                return@whenComplete
            }
            try {
                minecraft.execute {
                    try {
                        when (checkNotNull(result)) {
                            is CustomEmojiExportResult.Saved -> {
                                publish(minecraft, target)
                                playConfirmation(target)
                            }
                            is CustomEmojiExportResult.AlreadyExists -> publish(minecraft, target)
                            is CustomEmojiExportResult.TooLarge -> {
                                release(target.descriptor.originId)
                                logger.warn("Shared custom emoji {} exceeds the local file limit", target.asset.id)
                            }
                        }
                    } catch (processingFailure: RuntimeException) {
                        release(target.descriptor.originId)
                        logger.error("Failed to publish shared custom emoji {}", target.asset.id, processingFailure)
                    }
                }
            } catch (schedulingFailure: RuntimeException) {
                release(target.descriptor.originId)
                logger.error("Failed to schedule shared custom emoji publication {}", target.asset.id, schedulingFailure)
            }
        }
        return true
    }

    private fun publish(minecraft: Minecraft, target: CustomEmojiCopyTarget) {
        CustomEmojiRegistry.reloadWithResult(minecraft) {
            release(target.descriptor.originId)
        }
    }

    private fun playConfirmation(target: CustomEmojiCopyTarget) {
        try {
            EmotionSoundEngine.playCopyConfirmation(
                EmotifyClientConfig.settings().soundVolumePercent,
            )
        } catch (failure: RuntimeException) {
            logger.error("Failed to play shared custom emoji confirmation {}", target.asset.id, failure)
        }
    }

    private fun release(originId: CustomEmojiId) {
        if (!requests.complete(originId)) {
            logger.error("Lost ownership of custom emoji copy request {}", originId)
        }
    }

    private fun Vector3f.toInteractionVector(): InteractionVector3 =
        InteractionVector3(x().toDouble(), y().toDouble(), z().toDouble())

    private data class CustomEmojiCopyTarget(
        val asset: CustomEmojiAsset,
        val descriptor: CustomEmojiDescriptor,
    )
}
