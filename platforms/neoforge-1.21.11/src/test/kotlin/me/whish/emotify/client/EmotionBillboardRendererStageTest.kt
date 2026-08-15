package me.whish.emotify.client

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@Suppress("unused")
class EmotionBillboardRendererStageTest {
    @Test
    fun `NeoForge flushes emotion geometry after the weather batch`() {
        val contract = weatherMixinContract()

        contract.method shouldBe "lambda\$addWeatherPass\$4"
        contract.value shouldBe "INVOKE"
        contract.target shouldBe BUFFER_SOURCE_END_BATCH
        contract.shift shouldBe "AFTER"
    }

    @Test
    fun `emotion geometry uses the deferred buffer instead of the early render command collector`() {
        val contract = emotionRendererContract()

        contract.ordersSubmission shouldBe false
        contract.submitsCustomGeometry shouldBe false
        contract.referencesDeferredBuffer shouldBe true
    }
}

private fun weatherMixinContract(): WeatherMixinContract {
    var method: String? = null
    var value: String? = null
    var target: String? = null
    var shift: String? = null
    val classBytes = requireNotNull(
        EmotionBillboardRendererStageTest::class.java.getResourceAsStream(LEVEL_RENDERER_MIXIN),
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
                ): MethodVisitor? = if (name == WEATHER_HANDLER) {
                    object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                            if (descriptor == INJECT_ANNOTATION) {
                                injectVisitor(
                                    acceptMethod = { method = it },
                                    acceptValue = { value = it },
                                    acceptTarget = { target = it },
                                    acceptShift = { shift = it },
                                )
                            } else {
                                null
                            }
                    }
                } else {
                    null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return WeatherMixinContract(method, value, target, shift)
}

private fun injectVisitor(
    acceptMethod: (String) -> Unit,
    acceptValue: (String) -> Unit,
    acceptTarget: (String) -> Unit,
    acceptShift: (String) -> Unit,
): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
    override fun visitArray(name: String): AnnotationVisitor? = when (name) {
        "method" -> object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any) {
                acceptMethod(value as String)
            }
        }
        "at" -> object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor? =
                if (descriptor == AT_ANNOTATION) {
                    object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String, value: Any) {
                            when (name) {
                                "value" -> acceptValue(value as String)
                                "target" -> acceptTarget(value as String)
                            }
                        }

                        override fun visitEnum(name: String, descriptor: String, value: String) {
                            if (name == "shift") {
                                acceptShift(value)
                            }
                        }
                    }
                } else {
                    null
                }
        }
        else -> null
    }
}

private fun emotionRendererContract(): EmotionRendererContract {
    var ordersSubmission = false
    var submitsCustomGeometry = false
    var referencesDeferredBuffer = false
    val classBytes = requireNotNull(
        EmotionBillboardRenderer::class.java.getResourceAsStream("EmotionBillboardRenderer.class"),
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
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (owner == SUBMIT_NODE_COLLECTOR && name == "order") {
                            ordersSubmission = true
                        }
                        if (owner == ORDERED_SUBMIT_NODE_COLLECTOR && name == "submitCustomGeometry") {
                            submitsCustomGeometry = true
                        }
                        if (owner == DEFERRED_BUFFER) {
                            referencesDeferredBuffer = true
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return EmotionRendererContract(ordersSubmission, submitsCustomGeometry, referencesDeferredBuffer)
}

private data class EmotionRendererContract(
    val ordersSubmission: Boolean,
    val submitsCustomGeometry: Boolean,
    val referencesDeferredBuffer: Boolean,
)

private data class WeatherMixinContract(
    val method: String?,
    val value: String?,
    val target: String?,
    val shift: String?,
)

private const val SUBMIT_NODE_COLLECTOR = "net/minecraft/client/renderer/SubmitNodeCollector"
private const val ORDERED_SUBMIT_NODE_COLLECTOR = "net/minecraft/client/renderer/OrderedSubmitNodeCollector"
private const val DEFERRED_BUFFER = "me/whish/emotify/client/EmotionBillboardDeferredBuffer"
private const val LEVEL_RENDERER_MIXIN = "/me/whish/emotify/neoforge/mixin/client/LevelRendererMixin.class"
private const val WEATHER_HANDLER = "emotify\$afterWeather"
private const val INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;"
private const val AT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/At;"
private const val BUFFER_SOURCE_END_BATCH =
    "Lnet/minecraft/client/renderer/MultiBufferSource\$BufferSource;endBatch()V"
