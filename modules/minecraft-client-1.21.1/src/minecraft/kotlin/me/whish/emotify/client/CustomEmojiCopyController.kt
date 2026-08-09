package me.whish.emotify.client

import java.util.concurrent.CompletableFuture
import me.whish.emotify.client.custom.CustomEmojiCopyRequestGate
import me.whish.emotify.client.interaction.CustomEmotionCopyHitArea
import me.whish.emotify.client.interaction.EmotionBillboardHitDetector
import me.whish.emotify.client.interaction.EmotionInteractionRay
import me.whish.emotify.client.interaction.InteractionVector3
import me.whish.emotify.domain.CustomEmojiAsset
import me.whish.emotify.domain.CustomEmojiDescriptor
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult
import org.joml.Vector3f
import org.slf4j.LoggerFactory

object CustomEmojiCopyController {
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
        return CustomEmojiCopyPersistence.save(minecraft, target)
    }

    private fun findTarget(minecraft: Minecraft): CustomEmojiCopyTarget? {
        val level = minecraft.level ?: return null
        val localPlayer = minecraft.player ?: return null
        val cameraEntity = minecraft.cameraEntity ?: return null
        val partialTick = minecraft.timer.getGameTimeDeltaPartialTick(false)
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

    private fun Vector3f.toInteractionVector(): InteractionVector3 =
        InteractionVector3(x().toDouble(), y().toDouble(), z().toDouble())
}

private data class CustomEmojiCopyTarget(
    val asset: CustomEmojiAsset,
    val descriptor: CustomEmojiDescriptor,
)

private object CustomEmojiCopyPersistence {
    private val logger = LoggerFactory.getLogger("Emotify/CustomEmojiCopy")
    private val requests = CustomEmojiCopyRequestGate()

    fun save(minecraft: Minecraft, target: CustomEmojiCopyTarget): Boolean {
        if (CustomEmojiRegistry.containsOrigin(target.descriptor.originId)) {
            return false
        }
        if (!requests.tryBegin(target.descriptor.originId)) {
            return false
        }
        val export = try {
            CompletableFuture.supplyAsync(
                {
                    CustomEmojiAssetExporter.export(
                        CustomEmojiRegistry.directory(minecraft),
                        target.asset,
                        target.descriptor,
                    )
                },
                Util.ioPool(),
            )
        } catch (failure: RuntimeException) {
            check(requests.complete(target.descriptor.originId)) {
                "Custom emoji copy request ownership was lost before scheduling"
            }
            logger.error("Failed to schedule custom emoji copy {}", target.asset.id, failure)
            return false
        }
        export.whenComplete { result, failure ->
            if (!requests.complete(target.descriptor.originId)) {
                logger.error("Lost ownership of custom emoji copy request {}", target.descriptor.originId)
                return@whenComplete
            }
            minecraft.execute {
                if (failure != null) {
                    logger.error("Failed to save shared custom emoji {}", target.asset.id, failure)
                    return@execute
                }
                when (checkNotNull(result)) {
                    is CustomEmojiExportResult.Saved -> {
                        CustomEmojiRegistry.reload(minecraft)
                        EmotionSoundEngine.playCopyConfirmation(
                            EmotifyClientConfig.settings().soundVolumePercent,
                        )
                    }
                    is CustomEmojiExportResult.AlreadyExists -> CustomEmojiRegistry.reload(minecraft)
                    is CustomEmojiExportResult.TooLarge ->
                        logger.warn("Shared custom emoji {} exceeds the local file limit", target.asset.id)
                }
            }
        }
        return true
    }
}
