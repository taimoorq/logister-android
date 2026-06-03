package org.logister.android

/** Response returned by Logister after an ingest request. */
public class LogisterResponse(
    public val statusCode: Int,
    body: String? = ""
) {
    public val body: String = body ?: ""

    public val isAccepted: Boolean
        get() = statusCode in 200..299
}

