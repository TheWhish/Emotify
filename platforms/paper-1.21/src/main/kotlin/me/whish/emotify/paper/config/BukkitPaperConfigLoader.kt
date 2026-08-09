package me.whish.emotify.paper.config

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import me.whish.emotify.domain.EmotionCatalog
import me.whish.emotify.server.core.ServerConfigurationFileIO
import me.whish.emotify.server.core.ServerConfigurationSchema
import me.whish.emotify.server.core.ServerConfigurationVersion
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.NodeEvent

sealed interface PaperConfigLoadResult {
    data class Loaded(val config: PaperRuntimeConfig) : PaperConfigLoadResult

    data class Invalid(val violations: List<String>) : PaperConfigLoadResult

    data class FutureVersion(val version: Int) : PaperConfigLoadResult {
        init {
            require(version > ServerConfigurationSchema.CURRENT_VERSION)
        }
    }

    data class Failed(val failure: Exception) : PaperConfigLoadResult
}

class BukkitPaperConfigLoader(
    private val configFile: File,
    private val catalog: EmotionCatalog,
) {
    fun load(): PaperConfigLoadResult {
        val source = try {
            readBoundedUtf8()
        } catch (exception: ConfigInputViolation) {
            return PaperConfigLoadResult.Invalid(listOf(exception.message.orEmpty()))
        } catch (exception: Exception) {
            return PaperConfigLoadResult.Failed(exception)
        }
        val root = try {
            parseYaml(source)
        } catch (exception: Exception) {
            return PaperConfigLoadResult.Invalid(listOf("Invalid YAML: ${boundedMessage(exception)}"))
        }
        val flattened = flatten(root)
        if (flattened.violations.isNotEmpty()) {
            return PaperConfigLoadResult.Invalid(flattened.violations)
        }
        val version = try {
            ServerConfigurationSchema.classify(readDeclaredVersion(flattened.values[CONFIG_VERSION_PATH]))
        } catch (exception: ConfigInputViolation) {
            return PaperConfigLoadResult.Invalid(listOf(exception.message.orEmpty()))
        } catch (exception: IllegalArgumentException) {
            return PaperConfigLoadResult.Invalid(listOf(exception.message.orEmpty()))
        }
        if (version is ServerConfigurationVersion.Future) {
            return PaperConfigLoadResult.FutureVersion(version.value)
        }
        return when (
            val parsed = PaperRuntimeConfigParser.parse(
                PaperConfigDocument(flattened.values),
                catalog,
                version,
            )
        ) {
            is PaperConfigParseResult.Loaded -> {
                if (version == ServerConfigurationVersion.Legacy) {
                    try {
                        migrateLegacy(source, root, parsed.config)
                    } catch (exception: Exception) {
                        return PaperConfigLoadResult.Failed(exception)
                    }
                }
                PaperConfigLoadResult.Loaded(parsed.config)
            }
            is PaperConfigParseResult.Invalid -> PaperConfigLoadResult.Invalid(parsed.violations)
        }
    }

    private fun readDeclaredVersion(value: Any?): Int? = when (value) {
        null -> null
        is Byte,
        is Short,
        is Int,
        -> (value as Number).toInt()
        is Long -> {
            if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                throw ConfigInputViolation("config-version must fit a signed 32-bit integer: $value")
            }
            value.toInt()
        }
        else -> throw ConfigInputViolation("config-version must be an integer")
    }

    private fun migrateLegacy(source: String, root: Any?, config: PaperRuntimeConfig) {
        val path = configFile.toPath()
        val migrated = migratedSource(root, config)
        require(migrated.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_CONFIG_BYTES) {
            "Migrated config.yml exceeds the $MAXIMUM_CONFIG_BYTES byte limit"
        }
        ServerConfigurationFileIO.createBackupIfAbsent(
            path,
            path.resolveSibling("${path.fileName}.v0.bak"),
            MAXIMUM_CONFIG_BYTES,
        )
        ServerConfigurationFileIO.writeUtf8Atomically(path, migrated)
    }

    private fun migratedSource(root: Any?, config: PaperRuntimeConfig): String {
        val source = root as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val migrated = LinkedHashMap<Any?, Any?>(source.size + 1)
        migrated[CONFIG_VERSION_PATH] = ServerConfigurationSchema.CURRENT_VERSION
        source.forEach { (key, value) ->
            if (key != CONFIG_VERSION_PATH && key != COOLDOWN_MILLIS_PATH) {
                migrated[key] = value
            }
        }
        if (source.containsKey(COOLDOWN_MILLIS_PATH)) {
            migrated[COOLDOWN_MILLIS_PATH] = config.cooldownMillis
        }
        return Yaml(DUMPER_OPTIONS).dump(migrated)
    }

    private fun readBoundedUtf8(): String {
        val path = configFile.toPath()
        val declaredSize = Files.size(path)
        if (declaredSize > MAXIMUM_CONFIG_BYTES) {
            throw ConfigInputViolation("config.yml exceeds the $MAXIMUM_CONFIG_BYTES byte limit")
        }
        val bytes = Files.newInputStream(path).use { input ->
            input.readNBytes(MAXIMUM_CONFIG_BYTES + 1)
        }
        if (bytes.size > MAXIMUM_CONFIG_BYTES) {
            throw ConfigInputViolation("config.yml exceeds the $MAXIMUM_CONFIG_BYTES byte limit")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: Exception) {
            throw ConfigInputViolation("config.yml must contain valid UTF-8", exception)
        }
    }

    private fun parseYaml(source: String): Any? {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 0
            nestingDepthLimit = MAXIMUM_NESTING_DEPTH
            codePointLimit = MAXIMUM_CONFIG_BYTES
            allowRecursiveKeys = false
        }
        val yaml = Yaml(SafeConstructor(options))
        if (yaml.parse(source.reader()).any { event -> event is NodeEvent && event.anchor != null }) {
            throw ConfigInputViolation("YAML anchors and aliases are not supported")
        }
        return yaml.load(source.reader())
    }

    private fun flatten(root: Any?): FlattenedDocument {
        if (root == null) {
            return FlattenedDocument(emptyMap(), emptyList())
        }
        if (root !is Map<*, *>) {
            return FlattenedDocument(emptyMap(), listOf("Configuration root must be a mapping"))
        }
        val values = LinkedHashMap<String, Any?>()
        val violations = BoundedViolations()
        flattenMapping(root, "", values, violations)
        return FlattenedDocument(values, violations.snapshot())
    }

    private fun flattenMapping(
        mapping: Map<*, *>,
        prefix: String,
        values: MutableMap<String, Any?>,
        violations: BoundedViolations,
    ) {
        if (mapping.isEmpty() && prefix.isNotEmpty()) {
            violations.add("Configuration section cannot be empty: $prefix")
            return
        }
        for ((rawKey, value) in mapping) {
            if (values.size >= MAXIMUM_DOCUMENT_ENTRIES) {
                violations.add("Configuration contains more than $MAXIMUM_DOCUMENT_ENTRIES values")
                return
            }
            val key = rawKey as? String
            if (key == null || key.isBlank() || key.length > MAXIMUM_KEY_LENGTH) {
                violations.add("Configuration keys must be non-blank strings up to $MAXIMUM_KEY_LENGTH characters")
                continue
            }
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                null -> violations.add("Configuration value cannot be null: $path")
                is Map<*, *> -> flattenMapping(value, path, values, violations)
                is Collection<*> -> {
                    if (value.size > MAXIMUM_COLLECTION_ENTRIES) {
                        violations.add("$path contains more than $MAXIMUM_COLLECTION_ENTRIES entries")
                    } else {
                        values[path] = value.toList()
                    }
                }
                is Boolean,
                is Number,
                is String,
                -> values[path] = value
                else -> violations.add("Unsupported configuration value at $path")
            }
        }
    }

    private fun boundedMessage(exception: Exception): String {
        val message = exception.message?.replace(Regex("[\\r\\n]+"), " ")?.trim()
            ?.take(MAXIMUM_FAILURE_MESSAGE_LENGTH)
        return message?.takeIf(String::isNotEmpty) ?: exception.javaClass.simpleName
    }

    private data class FlattenedDocument(
        val values: Map<String, Any?>,
        val violations: List<String>,
    )

    private class BoundedViolations {
        private val values = ArrayList<String>()

        fun add(message: String) {
            if (values.size < MAXIMUM_VIOLATIONS) {
                values += message.take(MAXIMUM_FAILURE_MESSAGE_LENGTH)
            }
        }

        fun snapshot(): List<String> = java.util.List.copyOf(values)
    }

    private class ConfigInputViolation(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val CONFIG_VERSION_PATH = "config-version"
        const val COOLDOWN_MILLIS_PATH = "cooldown-millis"
        const val MAXIMUM_CONFIG_BYTES = 65_536
        const val MAXIMUM_NESTING_DEPTH = 4
        const val MAXIMUM_DOCUMENT_ENTRIES = 64
        const val MAXIMUM_COLLECTION_ENTRIES = 64
        const val MAXIMUM_KEY_LENGTH = 64
        const val MAXIMUM_VIOLATIONS = 32
        const val MAXIMUM_FAILURE_MESSAGE_LENGTH = 256
        val DUMPER_OPTIONS = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            indicatorIndent = 0
        }
    }
}
