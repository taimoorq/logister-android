package org.logister.android

import org.json.JSONObject

/** Transport used by LogisterClient. Tests and apps can provide their own implementation. */
public fun interface LogisterTransport {
    @Throws(Exception::class)
    public fun send(
        endpoint: String,
        apiKey: String,
        envelope: JSONObject,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): LogisterResponse
}

