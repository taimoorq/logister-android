package org.logister.android

/** Short-lived mobile ingest token returned by an app-controlled token provider. */
public class LogisterToken(
    public val token: String,
    public val expiresAtEpochSeconds: Long
) {
    internal fun isExpired(nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds <= nowEpochSeconds

    internal fun shouldRefresh(nowEpochSeconds: Long, refreshSkewSeconds: Long): Boolean =
        expiresAtEpochSeconds <= nowEpochSeconds + refreshSkewSeconds
}
