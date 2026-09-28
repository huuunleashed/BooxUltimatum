package app.booxultimatum.kit.log

/** Names for ApplicationExitInfo.REASON_* codes, kept as plain ints so the mapping tests on the JVM. */
internal object ExitReasons {
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    private const val INITIALIZATION_FAILURE = 7
    private const val EXCESSIVE_RESOURCE_USAGE = 9

    private val NAMES = arrayOf(
        "unknown", // 0 REASON_UNKNOWN
        "exit_self", // 1 REASON_EXIT_SELF
        "signaled", // 2 REASON_SIGNALED
        "low_memory", // 3 REASON_LOW_MEMORY
        "crash", // 4 REASON_CRASH
        "crash_native", // 5 REASON_CRASH_NATIVE
        "anr", // 6 REASON_ANR
        "initialization_failure", // 7 REASON_INITIALIZATION_FAILURE
        "permission_change", // 8 REASON_PERMISSION_CHANGE
        "excessive_resource_usage", // 9 REASON_EXCESSIVE_RESOURCE_USAGE
        "user_requested", // 10 REASON_USER_REQUESTED
        "user_stopped", // 11 REASON_USER_STOPPED
        "dependency_died", // 12 REASON_DEPENDENCY_DIED
        "other", // 13 REASON_OTHER
        "freezer", // 14 REASON_FREEZER (API 33)
        "package_state_change", // 15 REASON_PACKAGE_STATE_CHANGE (API 34)
        "package_updated", // 16 REASON_PACKAGE_UPDATED (API 34)
    )

    fun name(reason: Int): String = NAMES.getOrNull(reason) ?: "reason_$reason"

    /** Exits that mean something went wrong, logged at Warn. */
    fun isFailure(reason: Int): Boolean = reason == CRASH || reason == CRASH_NATIVE || reason == ANR ||
        reason == INITIALIZATION_FAILURE || reason == EXCESSIVE_RESOURCE_USAGE
}
