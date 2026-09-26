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
git tag -a v17.0.0 -m "17.0.0"
git push origin v17.0.0
GITHUB_ACTOR=<user> GITHUB_TOKEN=<token with write:packages> ./gradlew publish
```

`publish` uploads every library module (sources included) to GitHub Packages
under `io.github.kaeferfreund.socketio`. The token needs `write:packages`; never
commit it. Afterwards set `VERSION_NAME` to the next `-SNAPSHOT`.

## After publishing

Record the tag, commit, CI run and actual test results on the GitHub release,
together with the scope limits from `PARITY.md`. Robolectric and emulator runs
are not physical-device validation; say so if no device run was made.
