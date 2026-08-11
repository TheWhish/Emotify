package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@Suppress("unused")
class NeoForgeClientConstructionBoundaryTest : FunSpec({
    test("mod construction does not access the Minecraft singleton") {
        var accessesMinecraftSingleton = false
        val classBytes = requireNotNull(
            EmotifyClient::class.java.getResourceAsStream("EmotifyClient.class"),
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
                        if (name != "<init>") {
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
                                if (owner == "net/minecraft/client/Minecraft" && name == "getInstance") {
                                    accessesMinecraftSingleton = true
                                }
                            }
                        }
                    }
                },
                ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
        }

        accessesMinecraftSingleton shouldBe false
    }
})
