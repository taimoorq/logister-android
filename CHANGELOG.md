# Changelog

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
