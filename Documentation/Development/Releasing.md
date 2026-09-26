# Releasing

[Documentation index](../README.md) · [Testing](Testing.md)

A release is a reviewed commit, a version tag and the artifacts published from
it. Treat published versions as immutable: fix problems in a new version.

## Versions

The version is `VERSION_NAME` in `gradle.properties`; development builds end in
`-SNAPSHOT`. Versions follow semantic versioning: a change to a signature in an
`api/*.api` file is a new minor version at least, a removal or incompatible change
a new major version. `./gradlew apiCheck` fails on any unreviewed change.

## Before tagging

1. Update `CHANGELOG.md`: move the entries of the release out of "Unreleased"
   into a section `## <version> — <date>`, and describe behaviour changes and
   migration steps. This section becomes the text of the GitHub release.
2. Set `VERSION_NAME` to the release version and update the version in the
   installation snippets of the README, the getting started and the migration
   guide.
3. Review the guides for changed APIs and defaults, `PARITY.md` for scope changes,
   and the Kotlin, AGP, `minSdk` and JDK values in the README and the
   [compatibility guide](../Guides/Compatibility.md).
4. Push and require every CI job to pass on that exact commit, including
   `parity-gate`, `consumer` and both emulator jobs. Evidence from an earlier
   commit does not count, even if only documentation changed.
5. Copy the `parity-summary` artifact of that run to
   `Documentation/ReviewEvidence/ParitySummary.json` if its numbers differ.

## Publishing

```sh
git tag -a v17.0.1 -m "17.0.1"
git push origin v17.0.1
```

The tag starts `.github/workflows/release.yml`, which publishes the version
everywhere. Each job runs only after the one before it passed:

1. **Prepare**: the tag matches `VERSION_NAME`, the version is not a snapshot and
   CI passed on the tagged commit. `scripts/release-notes.py` writes the release
   notes from the `CHANGELOG.md` section and that CI run: its jobs, the parity
   summary and coverage. Nothing is published if this fails.
2. **Maven Central**: every library module (JAR or AAR, sources, Dokka javadoc,
   POM and Gradle module metadata, all GPG-signed) is uploaded, validated by
   Central and released.
3. **GitHub Packages**: the same signed artifacts.
4. **Verify**: every module's POM downloads from both repositories (Central
   takes 10 to 30 minutes), and `scripts/test-consumer.sh --maven-central`
   compiles the README quick start against Maven Central.
5. **GitHub release**: created with the notes from step 1 and marked latest.

A GitHub release therefore always means both repositories serve the version.
Maven Central cannot delete or replace a version: a mistake needs a new
version. If a later job fails, fix the cause and use "Re-run failed jobs"; do
not re-run the Maven Central job once it passed. Afterwards set `VERSION_NAME` to
the next `-SNAPSHOT`.

The workflow needs these repository secrets:

| Secret | Content |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A user token of the [Central Portal](https://central.sonatype.com) account that owns the namespace `io.github.kaeferfreund`, not the login |
| `SIGNING_KEY` | The ASCII-armored private GPG key (`gpg --export-secret-keys --armor <key id>`) |
| `SIGNING_KEY_PASSWORD` | Its passphrase |

The signing key is `6FAF6DA67E403DBD07B86B774A40A9D27353D6AD`. Its public key must
stay on `keyserver.ubuntu.com` (`gpg --keyserver keyserver.ubuntu.com --send-keys
<key id>`), where Central looks it up.

To check the artifacts without publishing, run `./gradlew publishToMavenLocal`.
Without a key the build skips signing; to sign locally, pass the key as the
Gradle properties `signingInMemoryKey` and `signingInMemoryKeyPassword`.

## After publishing

Read the GitHub release. Its notes state the tag, commit, CI run and actual test
results, point to the scope limits in `PARITY.md` and say that Robolectric and
emulator runs are not physical-device validation. If a device run was made, add
it to the notes by hand.
