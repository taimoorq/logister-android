package org.logister.android

/** Fetches short-lived mobile ingest tokens from the host app's trusted backend. */
public fun interface LogisterTokenProvider {
    @Throws(Exception::class)
    public fun fetchToken(): LogisterToken
}
