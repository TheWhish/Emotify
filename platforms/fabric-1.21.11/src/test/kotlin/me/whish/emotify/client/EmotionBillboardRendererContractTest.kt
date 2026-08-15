package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class EmotionBillboardRendererContractTest : FunSpec({
    test("Fabric defers billboards until the completed weather pass") {
        rendererMethodCalls(
            "render",
            setOf(SUBMIT_NODE_COLLECTOR, ORDERED_SUBMIT_NODE_COLLECTOR),
        ) shouldBe emptyList()
        rendererMethodCalls("render", EMOTION_BILLBOARD_DEFERRED_BUFFER) shouldBe listOf("consumer")
        rendererMethodCalls("flushAfterWeather", EMOTION_BILLBOARD_DEFERRED_BUFFER) shouldBe listOf("flush")
    }

    test("world rendering flushes billboards after the weather batch") {
        val mixin = Class.forName(LEVEL_RENDERER_MIXIN)
        val hook = mixin.declaredMethods.single { method -> method.name == "emotify\$afterWeather" }
        val injection = requireNotNull(hook.getAnnotation(Inject::class.java))
        val point = injection.at.single()

        injection.method.toList() shouldBe listOf("method_62216")
        point.value shouldBe "INVOKE"
        point.target shouldBe BUFFER_SOURCE_END_BATCH
        point.shift shouldBe At.Shift.AFTER
    }

    test("player rendering hooks the 1.21.11 submit contract at tail") {
        val mixin = Class.forName(LIVING_ENTITY_RENDERER_MIXIN)
        val hook = mixin.declaredMethods.single { method -> method.name == "emotify\$afterSubmit" }
        val injection = requireNotNull(hook.getAnnotation(Inject::class.java))

        injection.method.toList() shouldBe listOf(LIVING_ENTITY_SUBMIT)
        injection.at.single().value shouldBe "TAIL"
    }

    test("name tag suppression hooks the avatar submit contract at head") {
        val mixin = Class.forName(AVATAR_NAME_TAG_MIXIN)
        val hook = mixin.declaredMethods.single { method -> method.name == "emotify\$beforeSubmitNameTag" }
        val injection = requireNotNull(hook.getAnnotation(Inject::class.java))
        val point = injection.at.single()

        injection.method.toList() shouldBe listOf(AVATAR_NAME_TAG_SUBMIT)
        injection.cancellable shouldBe true
        point.value shouldBe "HEAD"
        point.shift shouldBe At.Shift.NONE
    }

    test("billboard vertices only populate the flat textured format") {
        rendererMethodCalls("vertex", VERTEX_CONSUMER).toSet() shouldBe setOf(
            "addVertex",
            "setColor",
            "setUv",
        )
    }
})

private fun rendererMethodCalls(methodName: String, ownerName: String): List<String> {
    return rendererMethodCalls(methodName, setOf(ownerName))
}

private fun rendererMethodCalls(methodName: String, ownerNames: Set<String>): List<String> {
    val calls = mutableListOf<String>()
    visitRendererMethod(methodName) {
        object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                if (owner in ownerNames) {
                    calls += name
                }
            }
        }
    }
    return calls
}

private fun visitRendererMethod(
    methodName: String,
    visitorFactory: () -> MethodVisitor,
) {
    val classBytes = requireNotNull(
        EmotionBillboardRendererContractTest::class.java.getResourceAsStream("/$EMOTION_BILLBOARD_RENDERER.class"),
    )
    classBytes.use { input ->
        ClassReader(input).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = if (name == methodName) visitorFactory() else null
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
}

private const val EMOTION_BILLBOARD_RENDERER = "me/whish/emotify/client/EmotionBillboardRenderer"
private const val EMOTION_BILLBOARD_DEFERRED_BUFFER = "me/whish/emotify/client/EmotionBillboardDeferredBuffer"
private const val SUBMIT_NODE_COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector"
private const val ORDERED_SUBMIT_NODE_COLLECTOR = "net/minecraft/client/renderer/OrderedSubmitNodeCollector"
private const val VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer"
private const val LIVING_ENTITY_RENDERER_MIXIN =
    "me.whish.emotify.fabric.mixin.client.LivingEntityRendererMixin"
private const val AVATAR_NAME_TAG_MIXIN = "me.whish.emotify.fabric.mixin.client.AvatarNameTagMixin"
private const val LEVEL_RENDERER_MIXIN = "me.whish.emotify.fabric.mixin.client.LevelRendererMixin"
private const val BUFFER_SOURCE_END_BATCH =
    "Lnet/minecraft/client/renderer/MultiBufferSource\$BufferSource;endBatch()V"
private const val LIVING_ENTITY_SUBMIT =
    "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;" +
        "Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/SubmitNodeCollector;" +
        "Lnet/minecraft/client/renderer/state/CameraRenderState;)V"
private const val AVATAR_NAME_TAG_SUBMIT =
    "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;" +
        "Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lnet/minecraft/client/renderer/SubmitNodeCollector;" +
        "Lnet/minecraft/client/renderer/state/CameraRenderState;)V"
