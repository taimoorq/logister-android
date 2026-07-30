# logister-android

Android SDK for sending errors, logs, metrics, transactions, spans, and scheduled-job check-ins to Logister.

The package supports Kotlin and Java applications on Android 6.0 (API 23) and newer. It uses `HttpURLConnection`, adds no third-party runtime networking dependency, and publishes as `org.logister:logister-android` on Maven Central.

## Before you start

Do not compile a long-lived Logister project API key into an Android app. Mobile apps must ask your own authenticated backend for a short-lived token. Your backend mints that token with `POST /api/v1/mobile_ingest_tokens`; the Android SDK caches it until it is close to expiring.

The request path is:

```text
Android app → your authenticated backend → Logister mobile token endpoint
Android app → Logister ingest endpoint with the short-lived token
```

## What it supports

- Single-module Android library configured for Gradle and Maven publication.
- Kotlin core with Java-compatible builders and async APIs for broad Android interop.
- Dependency-light HTTP transport using `HttpURLConnection`.
- Injectable transport for tests or alternate networking stacks.
- Token-provider based authentication with short-lived mobile ingest tokens.
- Async client methods for errors, logs, metrics, transactions, spans, and check-ins.
- Versioned canonical app, release, Android OS/API, device, lifecycle, and failure-mechanism context while retaining the original flat field aliases.
- Opt-in lifecycle sessions, rotating random installation pseudonyms, bounded breadcrumbs, uncaught-exception capture, Android 11+ historical exit/ANR capture, and a bounded disk retry queue.

Automatic collection is disabled until you provide an `Application` and enable each capability. Network spans remain manual.

## Install

Install the Android SDK from Maven Central:

```kotlin
dependencies {
    implementation("org.logister:logister-android:0.3.0")
}
```

- Maven Central: https://central.sonatype.com/artifact/org.logister/logister-android
- Maven repository path: https://repo1.maven.org/maven2/org/logister/logister-android/
- Android integration docs: https://logister.org/docs/integrations/android/

For local development, open this repository as an Android library project or include it as a composite build from an Android app.

## Quick start

Implement `LogisterTokenProvider` with your existing API client. The interface below represents the endpoint you add to your own backend:

```kotlin
import org.logister.android.LogisterToken
import org.logister.android.LogisterTokenProvider
import org.logister.android.captureExceptionAsync
import org.logister.android.LogisterExceptionDataPolicy
import org.logister.android.logisterClient

data class MobileTokenResponse(
    val token: String,
    val expiresAtEpochSeconds: Long
)

interface AppBackend {
    fun fetchLogisterMobileToken(): MobileTokenResponse
}

class AppBackendTokenProvider(
    private val appBackend: AppBackend
) : LogisterTokenProvider {
    override fun fetchToken(): LogisterToken {
        val response = appBackend.fetchLogisterMobileToken()
        return LogisterToken(response.token, response.expiresAtEpochSeconds)
    }
}

fun sendReadmeTest(appBackend: AppBackend) {
    val logister = logisterClient(
        baseUrl = "https://logister.example.com",
        tokenProvider = AppBackendTokenProvider(appBackend)
    ) {
        environment("development")
        release(BuildConfig.VERSION_NAME)
        packageName(BuildConfig.APPLICATION_ID)
        appVersion(BuildConfig.VERSION_NAME)
        buildNumber(BuildConfig.VERSION_CODE.toString())
    }

    logister.captureExceptionAsync(IllegalStateException("README test error")) {
        fingerprint("readme-test-error")
        context("screen_name", "Checkout")
    }
}
```

The capture methods enqueue work on the client's background executor and return a `Future<LogisterResponse>`. Do not block the Android main thread by calling `Future.get()` there. Open the Logister project inbox and confirm that **README test error** appears.

## Kotlin Usage

Building on `AppBackendTokenProvider` from the quick start, this example adds source context and sends several telemetry types.

```kotlin
import org.logister.android.captureExceptionAsync
import org.logister.android.captureMetricAsync
import org.logister.android.captureMessageAsync
import org.logister.android.captureTransactionAsync
import org.logister.android.logisterClient

val client = logisterClient(
    baseUrl = "https://your-logister-host.example",
    tokenProvider = AppBackendTokenProvider(appBackend)
) {
    environment("production")
    release("${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
    repository("acme/android-app")
    commitSha(BuildConfig.GIT_SHA)
    branch(BuildConfig.GIT_BRANCH)
    packageName(BuildConfig.APPLICATION_ID)
    appVersion(BuildConfig.VERSION_NAME)
    buildNumber(BuildConfig.VERSION_CODE.toString())
    buildType(BuildConfig.BUILD_TYPE)
    application(myApplication)
    exceptionDataPolicy(LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE)
    sessionTracking(true)
    installationTracking(true, rotationDays = 90)
    breadcrumbs(capacity = 50)
    offlineQueue(enabled = true, maxEvents = 30, maxBytes = 512 * 1024, maxAgeDays = 7)
    automaticCrashCapture(true, LogisterExceptionDataPolicy.TYPE_AND_STACKTRACE)
    applicationExitCapture(true)
}

client.addBreadcrumb(
    LogisterBreadcrumb.builder("Checkout opened")
        .category("navigation")
        .data("screen", "Checkout")
        .build()
)

client.captureMessageAsync("Checkout opened") {
    context("screen_name", "Checkout")
    sessionId("session-123")
}

client.captureMetricAsync("cart.item_count", 3, "count")

client.captureTransactionAsync("screen.load", 184.2) {
    context("screen_name", "Checkout")
}

try {
    runCheckout()
} catch (exception: Exception) {
    client.captureExceptionAsync(exception) {
        // Manual capture is a handled/reporting mechanism, not a fatal crash.
        mechanism("handled_exception")
        handled(true)
    }
}
```

The installation pseudonym is generated randomly, SHA-256 encoded, and rotated on the configured schedule. The SDK does not read Android ID, advertising ID, IMEI, or a hardware serial. Keep installation tracking, session tracking, breadcrumbs, and automatic handlers aligned with your consent and privacy policy.

Automatic crash capture uses `TYPE_AND_STACKTRACE` by default. It omits throwable messages and cause chains, persists the envelope before Android's previous uncaught-exception handler runs, and requires the durable offline queue. Manual `captureException` calls retain the 0.2.x full-detail policy unless the app sets `exceptionDataPolicy(TYPE_AND_STACKTRACE)` as shown above.

The offline queue stores at most the configured event and byte limits in app-private preferences and expires old entries after `maxAgeDays`. Token-provider failures are queued, so a sanitized crash can be sent after a later authenticated launch. A queued response has `isQueued == true` and `isAccepted == false`; it only becomes accepted after a later server response succeeds. Call `flushQueuedEventsAsync()` after authentication when the app may not immediately emit another event. Call `clearSessionBoundQueuedEvents()` on logout or account replacement; it removes events containing either `session_id` or `user_id` while retaining anonymous automatic crashes. `clearQueuedEvents()` removes everything.

Android 11+ historical exit capture records only the reason, importance, timestamp, and stable mechanism. Raw `ApplicationExitInfo.description` text is never included. When automatic crash capture is enabled, ordinary Java crash exits are not reported again through historical exit capture.

When the Logister project is connected to a GitHub repository, `repository`,
`commitSha`, and `branch` help source-aware error details resolve frames to the
right code. CI/CD systems should record release-to-commit deployment mappings
with the Logister HTTP API `POST /api/v1/deployments` endpoint.

## Spans And Check-ins

```kotlin
import org.logister.android.checkInAsync
import org.logister.android.captureSpanAsync
import org.logister.android.logisterSpan

client.captureSpanAsync(
    logisterSpan("trace-123", "GET /checkout", 42.5) {
        spanId("span-456")
        parentSpanId("span-root")
        kind("http")
        status("ok")
        context("screen_name", "Checkout")
    }
)

client.checkInAsync("daily-sync", "ok") {
    durationMs(812.4)
    context("expected_interval_seconds", 86_400)
}
```

## Java Interop

The Kotlin client classes remain Java-friendly, so Java apps can still use
`LogisterClient.builder(tokenProvider, baseUrl)`,
`LogisterEventOptions.builder(...)`, and `LogisterSpan.builder(...)` directly.

```java
import org.logister.android.LogisterClient;
import org.logister.android.LogisterEventOptions;
import org.logister.android.LogisterToken;
import org.logister.android.LogisterTokenProvider;

LogisterTokenProvider tokenProvider = () -> {
    MobileTokenResponse response = appBackend.fetchLogisterMobileToken();
    return new LogisterToken(response.getToken(), response.getExpiresAtEpochSeconds());
};

LogisterClient client = LogisterClient
    .builder(tokenProvider, "https://logister.example.com")
    .environment("development")
    .service("checkout-android")
    .build();

client.captureExceptionAsync(
    new IllegalStateException("README test error"),
    LogisterEventOptions.builder()
        .fingerprint("readme-test-error")
        .build()
);
```

## Development

Run the same checks used for release validation with a configured Android SDK:

```bash
./gradlew clean check javaDocReleaseGeneration --no-daemon
bash scripts/secret-scan.sh
```

If your Android SDK is installed outside the default location, set
`ANDROID_HOME` before running Gradle.

## Maven Central release

The Maven coordinates are `org.logister:logister-android:<version>`. The `org.logister` namespace is verified in Sonatype Central Portal, and GitHub Actions signs releases with an in-memory GPG key.

The release workflow uses these GitHub Actions secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

After CI passes on `main`, the release-from-main workflow creates the version tag from `gradle.properties` and explicitly dispatches `release.yml`. The release tests, signs, and uploads the package before creating the GitHub Release. Wait for both the public POM and AAR before calling the release complete:

```bash
curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.pom
curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.aar
gh release view vX.Y.Z
```

For `0.3.0`, commit the SDK changes with `VERSION_NAME=0.3.0`, its `CHANGELOG.md` section, and the matching README dependency example, then push or merge that commit to `main`. No manual tag is needed. Follow the `CI`, `Release from main`, and `Release` workflows in that order. If automation is interrupted before Maven Central accepts the version, re-run `Release` from the existing tag; never move a tag after publication:

```bash
gh workflow run release.yml --repo taimoorq/logister-android --ref v0.3.0 -f version=0.3.0
```

## Security and contributing

This repository is designed to be public and open source. Keep examples generic:
use placeholder short-lived mobile tokens, example hostnames, and environment
variables instead of real project credentials.

Do not commit Android signing keys, Logister project API keys, mobile token
issuer secrets, Cloudflare tokens, Maven Central credentials, `.env` files, or
`local.properties`.

CI runs `scripts/secret-scan.sh`, and dependency updates are tracked by
`.github/dependabot.yml` for Gradle and GitHub Actions.

The CI workflow does not need secrets. The Maven Central release workflow does;
set publishing credentials with the GitHub CLI:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo taimoorq/logister-android
gh secret set MAVEN_CENTRAL_PASSWORD --repo taimoorq/logister-android
gpg --armor --export-secret-keys YOUR_KEY_ID | gh secret set SIGNING_KEY --repo taimoorq/logister-android
gh secret set SIGNING_PASSWORD --repo taimoorq/logister-android
```

For server-side token issuance and mobile deployment guidance, read the [Android integration guide](https://logister.org/docs/integrations/android/) and the main app's [mobile add-ons reference](https://github.com/taimoorq/logister/blob/main/docs/mobile-add-ons.md).
