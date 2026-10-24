# Changelog

## v0.7.0 - 2026-10-23

Security and reliability hardening. Review the default changes before upgrading.

- Fixed a crash in the host app: a context with more keys than the item budget dropped the event UUID and evidence, and envelope building then threw `JSONException` on the calling thread. Envelope and event fields are now exempt from the item budget, `context` and each of its entries have their own budgets, truncation is marked with `context._truncated`, and oversized envelopes are reduced instead of dropped.
- Every event now stays within the ingest API's per-event limits (512 object keys counted at every level, about 1,000,000 estimated bytes, depth 16, 1,000 array items, 64 KiB message), which previously caused large crash events to be rejected with 413 and discarded. Content is kept in priority order: required event fields, the SDK's own context, the top exception's frames, ANR diagnostics, the newest breadcrumbs, exception causes, then caller context. `LogisterPayloadPolicy.Builder.maxEventKeys(...)` can lower the key cap.
- Capture never throws into the host app. Asynchronous capture builds and sanitizes the envelope on the client executor; build failures, throwing `beforeSend` hooks, and a shut-down executor return a dropped response. Non-finite numbers no longer fail an event.
- Fixed lost crash reports on slow networks: the offline queue no longer holds its storage lock during delivery, so a crash envelope is persisted immediately, and the post-crash flush runs on a dedicated thread.
- Adopted the shared Logister redaction spec. Keys are matched after removing all non-alphanumeric characters, with contains-matching on `token`, `secret`, `password`, `passwd`, `apikey`, `authorization`, `credential`, and `cookie`, so `accessToken`, `apiKey`, `x-api-key`, `refreshToken`, `clientSecret`, and `idToken` are now redacted. Sensitive values are replaced with `[REDACTED]` instead of the key being removed. Strings are also scrubbed for `Basic` credentials, JWTs, and `lgst_` keys.
- Behavior change: URLs found in strings now keep non-sensitive query parameters and redact only sensitive values and userinfo, matching the other SDKs. Previously the whole query string was removed.
- Default change: manual `captureException` now uses `TYPE_AND_STACKTRACE`. Set `exceptionDataPolicy(LogisterExceptionDataPolicy.FULL)` to keep messages and causes.
- Default change: `build()` rejects plain `http` endpoints unless the host is loopback or `10.0.2.2`, or `allowInsecureHttp(true)` is set.
- The default transport no longer follows redirects and caps response bodies at 64 KiB.
- Computed retry backoff now uses equal jitter. `Retry-After` is still honored exactly.
- Exception cause chains omit frames shared with the enclosing exception (`frames_in_common`) and are capped at 150 frames.
- The installation pseudonym is cached in memory, and legacy-state cleanup no longer commits preferences on the thread calling `build()`.

## v0.6.1 - 2026-09-28

- Record optional HTTP method, status code, failure category, attempt number and duration scope on traced calls for connected-project investigation.
- Preserve application results, origin allowlists, request handles and compatibility with older Logister servers.
- Capture DNS/connect failures before OkHttp network interception without duplicating an already recorded attempt.

## v0.6.0 - 2026-09-24

- Configure CI, release, and dependency submission to install platform-tools explicitly; the removed legacy tools package must not block SDK setup.

- Add immutable W3C request handles and an opt-in HttpURLConnection wrapper.
- Add the optional org.logister:logister-android-okhttp artifact, with call-local context and per-hop allowlist enforcement across redirects.

## v0.5.2 - 2026-09-11

- Added the independently verified Linux AAPT2 checksum required to assemble the release on hosted CI, and made pull-request CI assemble the same release artifact.
- Includes all 0.5.1 changes. The 0.5.1 tag failed before Maven publication and remains unchanged.

## v0.5.1 - 2026-09-11

- Updated AGP to 9.3.2, Gradle to 9.7.1, and the test JSON implementation to 20260814 while preserving Java 17 and Android API floors.
- Refreshed pinned GitHub Actions and dependency verification metadata.
- Restored explicit release dispatch and immutable-tag recovery from main; scheduled dependency checks cannot publish.
- Includes the previously unpublished durable-delivery and mobile-evidence improvements from 0.4 and 0.5.

## v0.5.0 - 2026-08-09

- Added bounded, structured Android 11+ ANR thread evidence from `ApplicationExitInfo.traceInputStream` without persisting the raw trace text, lock annotations, process command line, or other arbitrary lines.
- Added canonical byte measurements for the last system-sampled PSS and RSS, explicit exit status, typed diagnostic source/kind/evidence metadata, and truthful last-sample precision.
- Added the bounded actual crashing-thread name alongside the existing `crashed` role for live uncaught exceptions; reporting and sampled threads remain distinct.
- Kept historical capture off the main thread, retained the 0.4 process-run provenance and tenant-safe queue guarantees, and preserved the original grouping identity when server-side R8 enrichment arrives.

## v0.4.0 - 2026-08-09

- Added telemetry schema v3 with a UUID, exact UTC capture time, source/kind/capture/evidence facets, and an immutable context snapshot created before asynchronous delivery.
- Made Android 11+ historical exits durable before checkpointing and source-faithful across relaunches by using a bounded no-backup process-run journal; prior build facts are retained when proven and reported as unknown otherwise.
- Replaced the unscoped Auto Backup-eligible queue and installation state with endpoint/app/client-scoped no-backup storage. Ambiguous 0.3 state is discarded and reported locally rather than adopted under an unproved tenant.
- Added bounded retry backoff, `Retry-After`, one-time `401` refresh, poison-response discard, crash-lock deadlines, process policy, hook detachment, scoped consent purge, and non-sensitive client health.
- Added recursive sensitive-key and Bearer/URL scrubbing, payload depth/item/string/byte limits, and a final synchronous `beforeSend` hook that cannot replace event identity, time, or evidence provenance.
- Added automatic package/version/build/release, process, ABI, crash/reporting thread role, and fatality context while preserving existing builder and v2 alias compatibility.

## v0.3.0 - 2026-07-29

- Made automatic uncaught-exception capture privacy-safe by default: it records the exception type and bounded stack frames while omitting raw messages and cause chains.
- Persisted automatic crashes synchronously before delegating to Android's existing uncaught-exception handler, including when a mobile ingest token cannot yet be minted.
- Added bounded queue expiration, explicit flush/clear APIs, and account-bound cleanup that removes queued session- or user-identified events while retaining anonymous automatic crashes.
- Added capture-source and exception-data-policy metadata, removed raw Android app-exit descriptions, and avoided duplicate historical Java-crash events.
- Added an explicit full-detail policy for apps that intentionally opt into exception messages and causes; manual capture remains full-detail by default for compatibility.

## v0.2.0 - 2026-07-26

- Added the versioned Android telemetry contract with canonical app, device, OS, session, installation, lifecycle, and error-mechanism fields while retaining the existing flat aliases.
- Added opt-in lifecycle sessions, rotating random installation pseudonyms, bounded breadcrumbs, uncaught-exception capture, and Android 11+ historical ANR/crash/low-memory exit reporting.
- Added an opt-in bounded disk queue that retries transport failures and 429/5xx responses without treating queued delivery as server acceptance.
- Added explicit handled/unhandled, foreground, and screen options for manual capture and kept all identity/crash collection controls disabled until configured with an `Application`.

## v0.1.3 - 2026-07-25

- Migrated the build to Android Gradle Plugin 9.3.1 with built-in Kotlin and Gradle 9.6.1.
- Updated Maven Central publishing tooling and the JSON test dependency.
- Added checksum verification for the Gradle distribution and all resolved build dependencies.
- Pinned CI and release actions to immutable commits and removed the duplicate release dispatch path.

## v0.1.2 - 2026-06-18

- Added source context and deployment reporting support.
