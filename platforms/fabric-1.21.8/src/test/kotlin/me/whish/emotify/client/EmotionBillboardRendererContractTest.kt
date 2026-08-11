package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class EmotionBillboardRendererContractTest : FunSpec({
    test("Fabric flushes each transparency mode in its valid world stage") {
        rendererFieldReads("register", WORLD_RENDER_EVENTS) shouldContainExactlyInAnyOrder listOf(
            "AFTER_TRANSLUCENT",
        )
    }

    test("Fabric flushes the direct pass after the complete world frame graph") {
        val mixin = Class.forName(LEVEL_RENDERER_MIXIN)
        val hook = mixin.declaredMethods.single { method -> method.name == "emotify\$afterWorldFrameGraph" }
        val injection = requireNotNull(hook.getAnnotation(Inject::class.java))
        val point = injection.at.single()

        injection.method.toList() shouldBe listOf("renderLevel")
        point.value shouldBe "INVOKE"
        point.target shouldBe FRAME_GRAPH_EXECUTE
        point.shift shouldBe At.Shift.AFTER
        methodCalls(mixin, hook.name, EMOTION_BILLBOARD_RENDERER) shouldBe listOf("flushAfterWorldRendering")
    }

    test("billboard vertices only populate the flat textured format") {
        rendererMethodCalls("vertex", VERTEX_CONSUMER).toSet() shouldBe setOf(
            "addVertex",
            "setColor",
            "setUv",
        )
    }
})

private fun rendererFieldReads(methodName: String, ownerName: String): List<String> {
    val reads = mutableListOf<String>()
    visitRendererMethod(methodName) {
        object : MethodVisitor(Opcodes.ASM9) {
            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                if (opcode == Opcodes.GETSTATIC && owner == ownerName) {
                    reads += name
                }
            }
        }
    }
    return reads
}

private fun rendererMethodCalls(methodName: String, ownerName: String): List<String> {
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
                if (owner == ownerName) {
                    calls += name
                }
            }
        }
    }
    return calls
}

private fun methodCalls(type: Class<*>, methodName: String, ownerName: String): List<String> {
    val calls = mutableListOf<String>()
    val classBytes = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
    classBytes.use { input ->
        ClassReader(input).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = if (name == methodName) {
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (owner == ownerName) {
                                calls += name
                            }
                        }
                    }
                } else {
                    null
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
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
private const val LEVEL_RENDERER_MIXIN = "me.whish.emotify.fabric.mixin.client.LevelRendererMixin"
private const val WORLD_RENDER_EVENTS = "net/fabricmc/fabric/api/client/rendering/v1/WorldRenderEvents"
private const val VERTEX_CONSUMER = "com/mojang/blaze3d/vertex/VertexConsumer"
private const val FRAME_GRAPH_EXECUTE =
    "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder\$Inspector;)V"
