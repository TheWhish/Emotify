package me.whish.emotify.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.domain.EmotionId
import me.whish.emotify.domain.FakeMonotonicTimeSource
import me.whish.emotify.domain.FeatureFlags
import me.whish.emotify.domain.ProtocolCapabilities
import me.whish.emotify.domain.ProtocolVersion
import me.whish.emotify.protocol.ServerHello

@Suppress("unused")
class ClientHandshakeSessionTest : FunSpec({
    val localCapabilities = ProtocolCapabilities(ProtocolVersion.CURRENT, FeatureFlags.NONE)
    val validHello = ServerHello(localCapabilities, 1_200, BuiltInEmotionCatalog.catalog)

    test("pending connection times out at exactly five seconds") {
        val time = FakeMonotonicTimeSource()
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, time)
        session.begin(connectionId = 1L)

        time.advanceBy(4_999.milliseconds)
        session.pollTimeout()
        session.state.shouldBeInstanceOf<ClientHandshakeState.Pending>()

        time.advanceBy(1.milliseconds)
        session.pollTimeout()

        session.state shouldBe ClientHandshakeState.Unsupported(
            connectionId = 1L,
            reason = ClientHandshakeFailure.TIMEOUT,
        )
    }

    test("late hello restores support only for the active connection") {
        val time = FakeMonotonicTimeSource()
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, time)
        session.begin(connectionId = 1L)
        time.advanceBy(5_000.milliseconds)
        session.pollTimeout()

        session.receiveServerHello(connectionId = 1L, validHello) shouldBe ClientHandshakeTransition.SUPPORTED
        session.state.shouldBeInstanceOf<ClientHandshakeState.Supported>()

        session.begin(connectionId = 2L)
        session.receiveServerHello(connectionId = 1L, validHello) shouldBe ClientHandshakeTransition.IGNORED
        session.state.shouldBeInstanceOf<ClientHandshakeState.Pending>().connectionId shouldBe 2L
    }

    test("major mismatch disables only the feature and still reports client capabilities") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        val incompatibleHello = ServerHello(
            ProtocolCapabilities(ProtocolVersion(2, 0), FeatureFlags.NONE),
            1_200,
            BuiltInEmotionCatalog.catalog,
        )
        session.begin(connectionId = 1L)

        session.receiveServerHello(1L, incompatibleHello) shouldBe ClientHandshakeTransition.UNSUPPORTED
        session.state shouldBe ClientHandshakeState.Unsupported(1L, ClientHandshakeFailure.INCOMPATIBLE_PROTOCOL)
    }

    test("policy refresh updates immutable policy without a second client hello") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        session.begin(connectionId = 1L)
        session.receiveServerHello(1L, validHello)
        val refreshed = ServerHello(
            localCapabilities,
            2_000,
            EmotionCatalog.of(
                listOf(
                    EmotionId.of("emotify:love"),
                    EmotionId.of("server:unknown"),
                ),
            ),
        )

        session.receiveServerHello(1L, refreshed) shouldBe ClientHandshakeTransition.POLICY_UPDATED

        val supported = session.state.shouldBeInstanceOf<ClientHandshakeState.Supported>()
        supported.policy.cooldownMillis shouldBe 2_000
        supported.policy.allowedEmotions.ids.map(EmotionId::value) shouldContainExactly listOf("emotify:love")
    }

    test("changed protocol fields cannot restart an established handshake") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        session.begin(connectionId = 1L)
        session.receiveServerHello(1L, validHello)
        val changed = ServerHello(
            localCapabilities.copy(features = FeatureFlags(1L)),
            1_200,
            BuiltInEmotionCatalog.catalog,
        )

        session.receiveServerHello(1L, changed) shouldBe ClientHandshakeTransition.UNSUPPORTED
        session.state shouldBe ClientHandshakeState.Unsupported(1L, ClientHandshakeFailure.CHANGED_SERVER_CAPABILITIES)
    }

    test("disconnect ignores stale lifecycle events and clears the active connection") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        session.begin(connectionId = 2L)

        session.disconnect(connectionId = 1L)
        session.state.shouldBeInstanceOf<ClientHandshakeState.Pending>()

        session.disconnect(connectionId = 2L)
        session.state shouldBe ClientHandshakeState.Disconnected
    }

    test("duplicate server catalog disables only Emotify on the active connection") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        session.begin(connectionId = 2L)

        session.rejectDuplicateServerCatalog(connectionId = 1L) shouldBe ClientHandshakeTransition.IGNORED
        session.state.shouldBeInstanceOf<ClientHandshakeState.Pending>()
        session.rejectDuplicateServerCatalog(connectionId = 2L) shouldBe ClientHandshakeTransition.UNSUPPORTED
        session.state shouldBe ClientHandshakeState.Unsupported(
            connectionId = 2L,
            reason = ClientHandshakeFailure.DUPLICATE_SERVER_CATALOG,
        )
    }

    test("duplicate server catalog invalidates an established handshake") {
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, FakeMonotonicTimeSource())
        session.begin(connectionId = 1L)
        session.receiveServerHello(connectionId = 1L, validHello)

        session.rejectDuplicateServerCatalog(connectionId = 1L) shouldBe ClientHandshakeTransition.UNSUPPORTED
        session.state shouldBe ClientHandshakeState.Unsupported(
            connectionId = 1L,
            reason = ClientHandshakeFailure.DUPLICATE_SERVER_CATALOG,
        )
    }

    test("duplicate server catalog replaces a timeout with the precise failure") {
        val time = FakeMonotonicTimeSource()
        val session = ClientHandshakeSession(localCapabilities, BuiltInEmotionCatalog.catalog, time)
        session.begin(connectionId = 1L)
        time.advanceBy(5_000.milliseconds)
        session.pollTimeout()

        session.rejectDuplicateServerCatalog(connectionId = 1L) shouldBe ClientHandshakeTransition.UNSUPPORTED
        session.state shouldBe ClientHandshakeState.Unsupported(
            connectionId = 1L,
            reason = ClientHandshakeFailure.DUPLICATE_SERVER_CATALOG,
        )
    }
})
