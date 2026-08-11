package me.whish.emotify.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import me.whish.emotify.protocol.ServerHelloEnvelope
import me.whish.emotify.wire.v1.ProtocolV1Codecs
import me.whish.emotify.wire.v1.ProtocolV1PortableProfile

@Suppress("unused")
class BuiltInProtocolV1GoldenTest : FunSpec({
    test("built in server hello has a stable golden digest and boundary bytes") {
        val encoded = ProtocolV1Codecs.serverHello.encodeToByteArray(ServerHelloEnvelope.Valid(EmotifyProtocol.serverHello))

        encoded.size shouldBe 2_929
        encoded.sha256() shouldBe "12D407506DAA5C38911E6B18394E02A38F83E9A3373458D77FD90C640B2A57BC"
        encoded.copyOfRange(0, 32).toHex() shouldBe
            "01061FB817A20115656D6F746966793A6772696E6E696E675F6661636514656D"
        encoded.copyOfRange(encoded.size - 32, encoded.size).toHex() shouldBe
            "6D6F746966793A676F72696C6C6111656D6F746966793A6F72616E677574616E"
    }

    test("portable server hello profile accepts the built in catalog") {
        ProtocolV1PortableProfile.requireServerHello(EmotifyProtocol.serverHello) shouldBe EmotifyProtocol.serverHello
        ProtocolV1Codecs.serverHello.encodedSize(ServerHelloEnvelope.Valid(EmotifyProtocol.serverHello)) shouldBe 2_929
    }
})

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
