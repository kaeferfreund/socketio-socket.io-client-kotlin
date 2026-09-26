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

1. Update `CHANGELOG.md`: move the entries of the release out of "Unreleased",
   date it, and describe behaviour changes and migration steps.
2. Set `VERSION_NAME` to the release version and update the version in the
   README installation snippet.
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

The tag starts `.github/workflows/release.yml`. It stops unless the tag matches
`VERSION_NAME`, the version is not a snapshot and CI passed on the tagged commit.
It then uploads every library module (JAR or AAR, sources, Dokka javadoc, POM and
Gradle module metadata, all GPG-signed) to Maven Central and releases the
deployment once Central has validated it, and afterwards publishes the same
version to GitHub Packages under `io.github.kaeferfreund.socketio`. Maven Central
cannot delete or replace a version: a mistake needs a new version. Allow 10 to
30 minutes until a release can be downloaded. Afterwards set `VERSION_NAME` to
the next `-SNAPSHOT`.

The workflow needs these repository secrets:

| Secret | Content |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A user token of the [Central Portal](https://central.sonatype.com) account that owns the namespace `io.github.kaeferfreund`, not the login |
| `SIGNING_KEY` | The ASCII-armored private GPG key (`gpg --export-secret-keys --armor <key id>`) |
| `SIGNING_KEY_PASSWORD` | Its passphrase |

The public key must be on `keyserver.ubuntu.com` (`gpg --keyserver
keyserver.ubuntu.com --send-keys <key id>`), where Central looks it up.

To check the artifacts without publishing, run `./gradlew publishToMavenLocal`.
Without a key the build skips signing; to sign locally, pass the key as the
Gradle properties `signingInMemoryKey` and `signingInMemoryKeyPassword`.

## After publishing

Record the tag, commit, CI run and actual test results on the GitHub release,
together with the scope limits from `PARITY.md`. Robolectric and emulator runs
are not physical-device validation; say so if no device run was made.
