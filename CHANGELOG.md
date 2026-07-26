# Changelog

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
