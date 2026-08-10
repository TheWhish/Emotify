package me.whish.emotify.client.custom

import java.nio.file.Path

data class CustomEmojiSourceCacheEntry<T : Any>(
    val fingerprint: CustomEmojiDirectoryEntry,
    val value: T,
)

data class CustomEmojiSourceResolution<T : Any>(
    val file: CustomEmojiFile,
    val fingerprint: CustomEmojiDirectoryEntry,
    val cached: T?,
) {
    inline fun <R> resolve(
        reuse: (T) -> R,
        load: (CustomEmojiFile, CustomEmojiDirectoryEntry) -> R,
    ): R = cached?.let(reuse) ?: load(file, fingerprint)
}

fun <T : Any> planCustomEmojiSourceReuse(
    files: List<CustomEmojiFile>,
    fingerprint: CustomEmojiDirectoryFingerprint,
    previous: Map<Path, CustomEmojiSourceCacheEntry<T>>,
): List<CustomEmojiSourceResolution<T>> {
    require(files.size <= CustomEmojiFileScanner.MAXIMUM_FILES) {
        "Custom emoji reuse plan exceeds the file limit: ${files.size}"
    }
    require(previous.size <= CustomEmojiFileScanner.MAXIMUM_FILES) {
        "Custom emoji reuse cache exceeds the file limit: ${previous.size}"
    }
    val fingerprintsByName = fingerprint.entries.associateBy(CustomEmojiDirectoryEntry::fileName)
    val resolutions = files.map { file ->
        val sourceFingerprint = checkNotNull(fingerprintsByName[file.path.fileName.toString()]) {
            "Custom emoji fingerprint is missing for ${file.path}"
        }
        val cached = previous[file.path.normalize()]
            ?.takeIf { entry -> entry.fingerprint == sourceFingerprint }
            ?.value
        CustomEmojiSourceResolution(file, sourceFingerprint, cached)
    }
    return java.util.List.copyOf(resolutions)
}

fun customEmojiTextureIdsToRelease(
    previousTextureIds: Set<String>,
    retainedTextureIds: Set<String>,
): Set<String> {
    require(previousTextureIds.size <= CustomEmojiFileScanner.MAXIMUM_FILES) {
        "Previous custom emoji texture index exceeds the file limit: ${previousTextureIds.size}"
    }
    require(retainedTextureIds.size <= CustomEmojiFileScanner.MAXIMUM_FILES) {
        "Retained custom emoji texture index exceeds the file limit: ${retainedTextureIds.size}"
    }
    return java.util.Set.copyOf(previousTextureIds.filterTo(LinkedHashSet()) { textureId ->
        textureId !in retainedTextureIds
    })
}
