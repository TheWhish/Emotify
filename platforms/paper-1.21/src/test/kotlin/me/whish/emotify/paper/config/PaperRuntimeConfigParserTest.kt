package me.whish.emotify.paper.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import me.whish.emotify.catalog.builtin.BuiltInEmotionCatalog
import me.whish.emotify.domain.EmotionAnimation

@Suppress("unused")
class PaperRuntimeConfigParserTest : FunSpec({
    val catalog = BuiltInEmotionCatalog.catalog

    test("empty document resolves to safe production defaults") {
        val config = PaperRuntimeConfigParser.parse(PaperConfigDocument(emptyMap()), catalog)
            .shouldBeInstanceOf<PaperConfigParseResult.Loaded>()
            .config

        config.enabled shouldBe true
        config.customEmojisEnabled shouldBe true
        config.cooldownMillis shouldBe EmotionAnimation.DURATION_MILLIS.toInt()
        config.allowedEmotions shouldBe catalog
        config.broadcast.audience.radius shouldBe 64.0
        config.broadcast.audience.maximumTrackingCandidates shouldBe 256
        config.ingress.maximumQueuedMainThreadTasks shouldBe 512
    }

    test("custom emoji switch is independent from built in emotions") {
        val config = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(mapOf("custom-emojis.enabled" to false)),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Loaded>().config

        config.enabled shouldBe true
        config.customEmojisEnabled shouldBe false
        config.allowedEmotions shouldBe catalog
    }

    test("allow and deny lists produce one canonical catalog ordered like the manifest") {
        val first = catalog.ids[0]
        val second = catalog.ids[1]
        val third = catalog.ids[2]
        val result = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(
                mapOf(
                    "emotions.allow" to listOf(third.value, first.value),
                    "emotions.deny" to listOf(second.value),
                ),
            ),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Loaded>()

        result.config.allowedEmotions.ids shouldBe listOf(first, third)
    }

    test("ambiguous unknown and malformed emotion filters reject the complete snapshot") {
        val first = catalog.ids.first()
        val result = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(
                mapOf(
                    "emotions.allow" to listOf(first.value, first.value, "external:missing", "INVALID"),
                    "emotions.deny" to listOf(first.value),
                ),
            ),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Invalid>()

        result.violations.any { it.contains("duplicate") } shouldBe true
        result.violations.any { it.contains("unknown") } shouldBe true
        result.violations.any { it.contains("invalid") } shouldBe true
        result.violations.any { it.contains("both") } shouldBe true
    }

    test("every tunable value is rejected outside its compiled safety ceiling") {
        val invalidValues = mapOf(
            "config-version" to 2,
            "cooldown-millis" to 10_001,
            "broadcast.radius-blocks" to 64.01,
            "broadcast.maximum-tracking-candidates" to 257,
            "broadcast.global-burst-capacity" to 513,
            "broadcast.global-refill-per-second" to 257,
            "broadcast.region-burst-capacity" to 33,
            "broadcast.region-refill-per-second" to 17,
            "broadcast.maximum-regions" to 4_097,
            "ingress.maximum-queued-main-thread-tasks" to 513,
            "ingress.maximum-outstanding-selections" to 513,
            "ingress.global-burst-capacity" to 1_025,
            "ingress.global-refill-per-second" to 513,
        )

        val result = PaperRuntimeConfigParser.parse(PaperConfigDocument(invalidValues), catalog)
            .shouldBeInstanceOf<PaperConfigParseResult.Invalid>()

        invalidValues.keys.forEach { path -> result.violations.any { it.contains(path) } shouldBe true }
    }

    test("cooldown cannot undercut one complete emotion presentation") {
        val belowAnimation = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(mapOf("cooldown-millis" to EmotionAnimation.DURATION_MILLIS.toInt() - 1)),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Invalid>()
        val exactAnimation = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(mapOf("cooldown-millis" to EmotionAnimation.DURATION_MILLIS.toInt())),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Loaded>()
        val protocolMaximum = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(mapOf("cooldown-millis" to 10_000)),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Loaded>()

        belowAnimation.violations.any { it.contains("cooldown-millis") } shouldBe true
        exactAnimation.config.cooldownMillis shouldBe EmotionAnimation.DURATION_MILLIS.toInt()
        protocolMaximum.config.cooldownMillis shouldBe 10_000
    }

    test("non-finite radius and non-integral safety values are rejected") {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.0).forEach { radius ->
            PaperRuntimeConfigParser.parse(
                PaperConfigDocument(mapOf("broadcast.radius-blocks" to radius)),
                catalog,
            ).shouldBeInstanceOf<PaperConfigParseResult.Invalid>()
        }
        PaperRuntimeConfigParser.parse(
            PaperConfigDocument(mapOf("ingress.maximum-outstanding-selections" to 12.0)),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Invalid>()
    }

    test("unknown keys and wrong scalar types fail closed") {
        val result = PaperRuntimeConfigParser.parse(
            PaperConfigDocument(
                mapOf(
                    "enabled" to "true",
                    "custom-emojis.enabled" to "false",
                    "cooldown" to 3_000,
                ),
            ),
            catalog,
        ).shouldBeInstanceOf<PaperConfigParseResult.Invalid>()

        result.violations shouldContain "enabled must be a boolean"
        result.violations shouldContain "custom-emojis.enabled must be a boolean"
        result.violations shouldContain "Unknown configuration key: cooldown"
    }

    test("configuration state publishes complete immutable snapshots atomically") {
        val initial = PaperRuntimeConfigParser.parse(PaperConfigDocument(emptyMap()), catalog)
            .shouldBeInstanceOf<PaperConfigParseResult.Loaded>()
            .config
        val replacement = initial.copy(enabled = false, cooldownMillis = 4_000)
        val state = PaperConfigurationState(initial)

        state.replace(replacement) shouldBe initial
        state.current() shouldBe replacement
    }
})
