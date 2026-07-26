package org.logister.android

/** Response returned by Logister after an ingest request. */
public class LogisterResponse @JvmOverloads constructor(
    public val statusCode: Int,
    body: String? = "",
    public val isQueued: Boolean = false
) {
    public val body: String = body ?: ""

    public val isAccepted: Boolean
        get() = !isQueued && statusCode in 200..299

    public companion object {
        internal fun queued(): LogisterResponse = LogisterResponse(0, "queued for retry", true)
    }
}
