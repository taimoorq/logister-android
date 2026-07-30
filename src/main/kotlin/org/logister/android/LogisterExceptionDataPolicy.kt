package org.logister.android

/** Controls which throwable fields Logister serializes. */
public enum class LogisterExceptionDataPolicy(
    public val wireValue: String,
) {
    /** Exception type plus bounded stack frames. Messages and causes are omitted. */
    TYPE_AND_STACKTRACE("type_and_stacktrace"),

    /** Exception type, message, bounded stack frames, and a bounded cause chain. */
    FULL("full"),
}
