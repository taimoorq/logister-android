# logister-android

Android SDK for Logister.

This repository is the canonical home for the Android package add-on. Build Android client source, Gradle configuration, examples, tests, and release notes here rather than inside the Rails app.

## Current Scope

- Single-module Android library configured for Gradle and Maven publication.
- Kotlin core with Java-compatible builders and async APIs for broad Android interop.
- Dependency-light HTTP transport using `HttpURLConnection`.
- Injectable transport for tests or alternate networking stacks.
- Token-provider based authentication with short-lived mobile ingest tokens.
- Async client methods for errors, logs, metrics, transactions, spans, and check-ins.
- Capture Android app metadata such as package name, version name, version code, build type, Android version, API level, device model, locale, and session ID.

Automatic crash, screen, network, retry, and offline-queue instrumentation should remain opt-in while privacy defaults are settled.

## Install

Install the Android SDK from Maven Central:

```kotlin
dependencies {
    implementation("org.logister:logister-android:0.1.2")
}
```

- Maven Central: https://central.sonatype.com/artifact/org.logister/logister-android
- Maven repository path: https://repo1.maven.org/maven2/org/logister/logister-android/
- Android integration docs: https://docs.logister.org/integrations/android/

For local development, open this repository as an Android library project or include it as a composite build from an Android app.

## Maven Central Release

The Maven coordinates are:

```text
org.logister:logister-android:<version>
```

The `org.logister` namespace is verified in Sonatype Central Portal. Release
signing uses an in-memory GPG key from GitHub Actions secrets.

The release workflow uses GitHub Actions secrets, not checked-in credentials:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`

After CI passes on `main`, the release-from-main workflow creates the matching
version tag from `gradle.properties` and dispatches the release workflow. You
can also push a semantic version tag manually:

```bash
git tag v0.1.2
git push origin v0.1.2
```

After the workflow succeeds, the signed artifacts are automatically released
through Maven Central Portal and may take a few minutes to appear in Maven
Central.

## Kotlin Usage

Do not compile a Logister project API key into an Android app. The Android SDK
requires a `LogisterTokenProvider`; implement it by calling your own backend.
Your backend should authenticate the app/session, use its server-side Logister
project API key to mint a short-lived token with
`POST /api/v1/mobile_ingest_tokens`, and return that token to the app.

```kotlin
import org.logister.android.captureExceptionAsync
import org.logister.android.captureMetricAsync
import org.logister.android.captureMessageAsync
import org.logister.android.captureTransactionAsync
import org.logister.android.LogisterToken
import org.logister.android.LogisterTokenProvider
import org.logister.android.logisterClient

class AppBackendTokenProvider : LogisterTokenProvider {
    override fun fetchToken(): LogisterToken {
        // Call your app backend, not Logister directly. Return the token and
        // expires_at value from your backend's mobile token response.
        return LogisterToken("short-lived-mobile-token", System.currentTimeMillis() / 1000 + 900)
    }
}

val client = logisterClient(
    baseUrl = "https://your-logister-host.example",
    tokenProvider = AppBackendTokenProvider()
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
}

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
    client.captureExceptionAsync(exception)
}
```

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

## Verification

Run the Android unit tests with:

```bash
./gradlew test
```

If your Android SDK is installed outside the default location, set
`ANDROID_HOME` before running Gradle.

## Public Repository Hygiene

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

The Rails-side integration plan lives in the `logister` Rails repository under
`docs/cloudflare-mobile-integrations-plan.md`.
