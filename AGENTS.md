# Logister Android SDK Agent Notes

This is a public Maven package repository. Never commit Sonatype credentials,
signing keys, private telemetry, customer data, or local Android configuration.

## Dependency and build-tool maintenance

- `gradle.properties` `VERSION_NAME` is the package-version source of truth.
- Keep the Android Gradle Plugin, Gradle wrapper, JDK, compile SDK, and Kotlin
  model compatible. AGP 9 supplies built-in Kotlin; do not add the external
  Kotlin Android plugin unless the build model changes and CI proves the need.
- The Gradle distribution must have `distributionSha256Sum`, and the wrapper JAR
  must match an official Gradle checksum.
- `gradle/verification-metadata.xml` is strict and covers build plugins as well
  as library dependencies. When a legitimate task resolves new artifacts, use
  `--write-verification-metadata sha256` for that exact task, inspect the diff,
  and independently compare every new hash with Maven Central before commit.
- Keep Gradle and GitHub Actions Dependabot updates enabled. Pin Actions to full
  commit SHAs and keep the repository Actions allowlist aligned with those SHAs.
- Dependency submission intentionally includes only configurations matching
  `.*(Compile|Runtime)Classpath$`. Do not report Android Gradle Plugin or
  publisher internals as dependencies of the shipped SDK; review build-tool
  advisories separately.

## Verification

Run with a configured Android SDK before handoff or release:

```bash
./gradlew clean check javaDocReleaseGeneration --no-daemon
bash scripts/secret-scan.sh
```

If publication introduces new Dokka/Javadoc artifacts, generate and verify the
metadata before retrying. Do not disable dependency verification.

## Release contract

- Update `VERSION_NAME` and `CHANGELOG.md` together.
- Merging a new version to `main` runs CI, creates `vX.Y.Z`, and explicitly
  dispatches `release.yml`. Keep the explicit dispatch because tags pushed with
  `GITHUB_TOKEN` do not start tag-push workflows.
- Release automation only tags the commit when it is still the tip of `main`.
  CI validates that `VERSION_NAME`, the changelog heading, and the README Maven
  coordinate agree before running the full release check suite.
- The release tests, signs, and uploads an automatic Sonatype Central Portal
  deployment before creating the GitHub Release. The upload task may finish
  while Central says the deployment is being published; wait for the public
  POM and AAR before calling the release complete.
- A failed pre-publication run can leave a tag without a package or GitHub
  Release. Only in that narrow case may the failed tag be repaired to the
  verified commit. Never move a tag after Maven Central accepted the version.
- Verify all release surfaces:

```bash
curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.pom
curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.aar
gh release view vX.Y.Z
```
