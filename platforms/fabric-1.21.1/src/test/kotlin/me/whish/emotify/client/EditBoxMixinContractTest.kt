package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class EditBoxMixinContractTest : FunSpec({
    test("configured drawString targets match the 1.21.1 EditBox contract") {
        configuredMixinTargets() shouldBe EXPECTED_DRAW_STRING_TARGETS
    }

    test("Fabric 1.21.1 EditBox.renderWidget invokes expected drawString overloads") {
        val stream = EditBoxMixinContractTest::class.java.getResourceAsStream("/net/minecraft/client/gui/components/EditBox.class")
        requireNotNull(stream) { "EditBox.class not found on classpath" }
        val drawStringCalls = mutableListOf<String>()
        stream.use { input ->
            ClassReader(input).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (name == "renderWidget" || name == "method_48579") {
                            return object : MethodVisitor(Opcodes.ASM9) {
                                override fun visitMethodInsn(
                                    opcode: Int,
                                    owner: String,
                                    name: String,
                                    descriptor: String,
                                    isInterface: Boolean,
                                ) {
                                    if (name == "drawString") {
                                        drawStringCalls += owner + "." + name + descriptor
                                    }
                                }
                            }
                        }
                        return null
                    }
                },
                0,
            )
        }
        drawStringCalls shouldBe listOf(
            "net/minecraft/client/gui/GuiGraphics.drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I",
            "net/minecraft/client/gui/GuiGraphics.drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I",
            "net/minecraft/client/gui/GuiGraphics.drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
            "net/minecraft/client/gui/GuiGraphics.drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I",
            "net/minecraft/client/gui/GuiGraphics.drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I",
        )
    }
})

private fun configuredMixinTargets(): Set<String> {
    val targets = linkedSetOf<String>()
    val classBytes = requireNotNull(
        EditBoxMixinContractTest::class.java.getResourceAsStream(
            "/me/whish/emotify/fabric/mixin/client/EditBoxMixin.class",
        ),
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
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                        if (descriptor != WRAP_OPERATION_ANNOTATION) {
                            return null
                        }
                        return object : AnnotationVisitor(Opcodes.ASM9) {
                            override fun visitArray(name: String): AnnotationVisitor? {
                                if (name != "at") {
                                    return null
                                }
                                return object : AnnotationVisitor(Opcodes.ASM9) {
                                    override fun visitAnnotation(
                                        name: String?,
                                        descriptor: String,
                                    ): AnnotationVisitor? {
                                        if (descriptor != AT_ANNOTATION) {
                                            return null
                                        }
                                        return object : AnnotationVisitor(Opcodes.ASM9) {
                                            override fun visit(name: String, value: Any) {
                                                if (name == "target") {
                                                    targets += value as String
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return targets
}

private const val WRAP_OPERATION_ANNOTATION =
    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"
private const val AT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/At;"
private val EXPECTED_DRAW_STRING_TARGETS = setOf(
    "Lnet/minecraft/client/gui/GuiGraphics;drawString" +
        "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I",
    "Lnet/minecraft/client/gui/GuiGraphics;drawString" +
        "(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I",
    "Lnet/minecraft/client/gui/GuiGraphics;drawString" +
        "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I",
)
