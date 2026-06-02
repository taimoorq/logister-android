package org.logister.android;

import org.json.JSONObject;

/** Transport used by LogisterClient. Tests and apps can provide their own implementation. */
public interface LogisterTransport {
    LogisterResponse send(
            String endpoint,
            String apiKey,
            JSONObject envelope,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws Exception;
}
