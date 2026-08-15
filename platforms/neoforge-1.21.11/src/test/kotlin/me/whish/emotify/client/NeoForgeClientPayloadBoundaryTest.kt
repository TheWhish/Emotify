package me.whish.emotify.client

import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import java.nio.charset.StandardCharsets
import me.whish.emotify.network.EmotifyNetwork
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@Suppress("unused")
class NeoForgeClientPayloadBoundaryTest {
    @Test
    fun `common payload registration remains dedicated-server safe`() {
        val constants = classConstants(EmotifyNetwork::class.java)

        constants.contains("net/minecraft/client/") shouldBe false
        constants.contains("me/whish/emotify/client/") shouldBe false
        constants.contains("RegisterClientPayloadHandlersEvent") shouldBe false
    }

    @Test
    fun `physical client registration installs every clientbound Protocol 1 handler`() {
        clientPayloadRegistrationCount() shouldBe CLIENTBOUND_PAYLOAD_COUNT
    }
}

private fun classConstants(type: Class<*>): String {
    val bytes = requireNotNull(type.getResourceAsStream("${type.simpleName}.class")).use { input ->
        input.readAllBytes()
    }
    return String(bytes, StandardCharsets.ISO_8859_1)
}

private fun clientPayloadRegistrationCount(): Int {
    var registrationCount = 0
    val classBytes = requireNotNull(
        NeoForgeClientPayloadRegistration::class.java.getResourceAsStream(
            "NeoForgeClientPayloadRegistration.class",
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
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        if (owner == CLIENT_PAYLOAD_EVENT && name == "register") {
                            registrationCount += 1
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return registrationCount
}

private const val CLIENT_PAYLOAD_EVENT =
    "net/neoforged/neoforge/client/network/event/RegisterClientPayloadHandlersEvent"
private const val CLIENTBOUND_PAYLOAD_COUNT = 6
