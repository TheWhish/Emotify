package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class EmotionPickerScrollbarPortContractTest : FunSpec({
    test("1.21.8 decorations do not render the scrollbar a second time") {
        emotionGridListCalls("renderDecorations", "renderScrollbar") shouldBe 0
    }

    test("scrollbar rendering owns the visibility guard") {
        firstEmotionGridListCall("renderScrollbar") shouldBe "scrollbarVisible"
    }
})

private fun emotionGridListCalls(methodName: String, invokedName: String): Int {
    var calls = 0
    visitEmotionGridListMethod(methodName) { owner, name ->
        if (owner == EMOTION_GRID_LIST && name == invokedName) {
            calls++
        }
    }
    return calls
}

private fun firstEmotionGridListCall(methodName: String): String? {
    var firstCall: String? = null
    visitEmotionGridListMethod(methodName) { owner, name ->
        if (owner == EMOTION_GRID_LIST && firstCall == null) {
            firstCall = name
        }
    }
    return firstCall
}

private fun visitEmotionGridListMethod(
    methodName: String,
    visitor: (owner: String, name: String) -> Unit,
) {
    val classBytes = requireNotNull(
        EmotionPickerScrollbarPortContractTest::class.java.getResourceAsStream("/$EMOTION_GRID_LIST.class"),
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
                    if (name != methodName) {
                        return null
                    }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            visitor(owner, name)
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
}

private const val EMOTION_GRID_LIST = "me/whish/emotify/client/EmotionGridList"
