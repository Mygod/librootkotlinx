package be.mygod.librootkotlinx.impl

/** Exit statuses reserved for startup failures that librootkotlinx terminates explicitly. */
internal enum class RootProcessExit(
    val code: Int,
    val description: String,
    val phase: Phase,
) {
    STARTUP_MARKER_OPEN_FAILED(100, "startup marker open failed", Phase.BEFORE_STARTUP_MARKER),
    APP_PROCESS_RELOCATION_FAILED(101, "app_process relocation failed", Phase.BEFORE_STARTUP_MARKER),
    APP_PROCESS_RESOLUTION_FAILED(102, "app_process resolution failed", Phase.BEFORE_STARTUP_MARKER),
    APP_PROCESS_PATH_INVALID(103, "resolved app_process path invalid", Phase.BEFORE_STARTUP_MARKER),
    STARTUP_MARKER_WRITE_FAILED(104, "startup marker write failed", Phase.BEFORE_STARTUP_MARKER),
    BOOTSTRAP_ARGUMENTS_INVALID(105, "bootstrap arguments invalid", Phase.AFTER_STARTUP_MARKER),
    OWNERSHIP_CONNECTION_FAILED(106, "ownership connection failed", Phase.AFTER_STARTUP_MARKER),
    ROOT_MAIN_RETURNED(107, "root main returned", Phase.AFTER_STARTUP_MARKER),
    ;

    enum class Phase {
        BEFORE_STARTUP_MARKER,
        AFTER_STARTUP_MARKER,
    }

    val shellFailureCommand
        get() = "{ printf '%s\\n' 'librootkotlinx: $description' >&2; exit $code; }"

    companion object {
        fun fromCode(code: Int, phase: Phase) = entries.find { it.code == code && it.phase == phase }
    }
}
