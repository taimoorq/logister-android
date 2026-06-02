package org.logister.android;

/** Response returned by Logister after an ingest request. */
public final class LogisterResponse {
    private final int statusCode;
    private final String body;

    public LogisterResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body == null ? "" : body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public boolean isAccepted() {
        return statusCode >= 200 && statusCode < 300;
    }
}
