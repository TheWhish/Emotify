package me.whish.emotify.client.custom

fun canRemoveMissingCustomQuickSlots(
    decodeFailures: Int,
    rejectedFiles: Int,
    capacityRejections: Int,
    directoryLimitReached: Boolean,
): Boolean {
    require(decodeFailures >= 0) { "Decode failure count must be non-negative" }
    require(rejectedFiles >= 0) { "Rejected file count must be non-negative" }
    require(capacityRejections >= 0) { "Capacity rejection count must be non-negative" }
    return decodeFailures == 0 &&
        rejectedFiles == 0 &&
        capacityRejections == 0 &&
        !directoryLimitReached
}
