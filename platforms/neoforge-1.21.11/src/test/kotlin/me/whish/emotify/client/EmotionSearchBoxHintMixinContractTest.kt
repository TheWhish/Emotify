package me.whish.emotify.client

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class EmotionSearchBoxHintMixinContractTest {
    @Test
    fun `NeoForge removes the forced vanilla shadow from Emotify search hints`() {
        configuredHintTarget() shouldBe HINT_DRAW_CALL
    }
}

private fun configuredHintTarget(): String? {
    var target: String? = null
    val classBytes = requireNotNull(
        EmotionSearchBoxHintMixinContractTest::class.java.getResourceAsStream(MIXIN_CLASS),
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
                ): MethodVisitor? {
                    if (name != HANDLER_NAME) {
                        return null
                    }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                            if (descriptor != WRAP_OPERATION_ANNOTATION) {
                                return null
                            }
                            return hintTargetVisitor { value -> target = value }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return target
}

private fun hintTargetVisitor(accept: (String) -> Unit): AnnotationVisitor =
    object : AnnotationVisitor(Opcodes.ASM9) {
        override fun visitArray(name: String): AnnotationVisitor? {
            if (name != "at") {
                return null
            }
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor? {
                    if (descriptor != AT_ANNOTATION) {
                        return null
                    }
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String, value: Any) {
                            if (name == "target") {
                                accept(value as String)
                            }
                        }
                    }
                }
            }
        }
    }

private const val MIXIN_CLASS = "/me/whish/emotify/neoforge/mixin/client/EditBoxHintMixin12111.class"
private const val HANDLER_NAME = "emotify\$drawHint"
private const val WRAP_OPERATION_ANNOTATION =
    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"
private const val AT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/At;"
private const val HINT_DRAW_CALL =
    "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"
