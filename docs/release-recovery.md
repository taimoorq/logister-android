# Recover Maven Central publication

Use this guide when an upload was accepted but the release workflow did not finish.

Central publication can take [10–30 minutes](https://vanniktech.github.io/gradle-maven-publish-plugin/central/#publishing-releases). The release workflow passes `-PmavenCentralDeploymentValidation=PUBLISHED` to the pinned publisher plugin, waits for that deployment, then downloads the public AAR and compares it with the tested archive. The tested unsigned AAR is retained as a workflow artifact for 30 days even if the later publication step fails.

1. Find the deployment ID and its last state in the upload step's log. Inspect that deployment in Central Portal if the publisher timed out.
2. If the deployment is still being published, wait for it. Do not upload the same version again or replace its tag.
3. Verify the exact version's public POM and AAR, replacing `X.Y.Z` below:

   ```bash
   curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.pom
   curl -fsSI https://repo1.maven.org/maven2/org/logister/logister-android/X.Y.Z/logister-android-X.Y.Z.aar
   ```

4. Once both files are public, dispatch recovery from the existing immutable tag:

   ```bash
   gh workflow run release.yml --repo taimoorq/logister-android --ref main -f tag=vX.Y.Z
   ```

The version check skips upload. The workflow checks the reviewed tag, rebuilds and compares the archive with the public AAR, and completes the GitHub Release only after byte verification. If bytes differ, stop and compare the public file with the retained original workflow artifact; do not publish another artifact over that version.

Keep workflow-only recovery changes outside the published SDK inputs. They do not require a new SDK version or rewriting a consumed tag.
