package org.logister.android

/** Response returned by Logister after an ingest request. */
public class LogisterResponse @JvmOverloads constructor(
    public val statusCode: Int,
    body: String? = "",
    public val isQueued: Boolean = false,
) {
    public val body: String = body ?: ""
    public var retryAfterMillis: Long? = null
        private set

    public val isDropped: Boolean
        get() = !isQueued && statusCode == 0 && body.startsWith(DROPPED_PREFIX)

    public val isAccepted: Boolean
        get() = !isQueued && statusCode in 200..299

    public companion object {
        internal fun queued(): LogisterResponse = LogisterResponse(0, "queued for retry", true)
        internal fun withRetryAfter(statusCode: Int, body: String?, retryAfterMillis: Long?): LogisterResponse =
            LogisterResponse(statusCode, body).also { it.retryAfterMillis = retryAfterMillis }

        internal fun dropped(reason: String): LogisterResponse =
            LogisterResponse(0, "$DROPPED_PREFIX$reason")

        internal fun collectionDisabled(): LogisterResponse = dropped("collection disabled")

        private const val DROPPED_PREFIX: String = "dropped: "
    }
}
