# Contributing

[Documentation](Documentation/README.md) · [Repository layout](Documentation/Development/RepositoryLayout.md)

This library is a Socket.IO 4 / Engine.IO 4 client for Kotlin and Android that
follows the official JavaScript client at a pinned commit. Start with a small,
focused change and explain which user-visible behaviour or maintenance problem it
addresses.

## Set up and validate

You need JDK 17 and 21, the Android SDK (platform 37), Node.js 24, Python 3.9+ and the OpenSSL CLI.
The [testing guide](Documentation/Development/Testing.md#local-setup) lists what
each tool is used for.

```sh
(cd fixtures && npm ci --ignore-scripts --no-audit --no-fund)
./gradlew check
python3 scripts/test-documentation.py
python3 scripts/check-documentation.py
python3 scripts/test-review-regressions.py
python3 scripts/check-parity-contracts.py --strict --junit '*/build/test-results/**/*.xml'
```

Do not present a filtered run, a static check or an earlier commit's results as
a full run.

## Code

- All protocol state lives on the manager's `ProtocolExecutor`. Do not add locks
  or `@Volatile` fields to make cross-thread access "mostly work"; post to the
  executor instead.
- Treat everything received from the network as hostile: a malformed packet may
  fail the connection with `parse error`, never crash or hang it.
- Port behaviour from the pinned JavaScript source and keep its method names, so
  both can be compared side by side. Differences need a reason in a comment and
  in the documentation.
- The public API is explicit (`explicitApi()`), documented with KDoc, and checked
  against `api/*.api`. Run `./gradlew apiDump` only for intended changes and
  review the diff.
- Warnings are errors. Fix a ktlint, detekt or lint finding instead of
  suppressing it; where a suppression is right, say why next to it.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`fix:`, `feat:`, `docs:`, `test:`, `build:`, `refactor:`).

## Tests

Write the test first and watch it fail. Test failure, reconnect and cancellation
paths, not only the successful one. Wait for events with deadlines or use virtual
time; never `sleep` to prove something. A disabled, skipped or flaky test is not
acceptable: fix the cause.

Parity contracts refer to exact test files, classes and methods. Renaming a test
means updating its contract in the same change. Changing a contract, an exclusion
or the pinned upstream commit needs an explicit review; never relax a gate to
make CI pass.

## Documentation

Keep the README an entry point. Application recipes go to
`Documentation/Guides`, contributor material to `Documentation/Development`;
link new pages from the [documentation index](Documentation/README.md). Update the
guide that describes an API or default you change. The marked README quick start
is compiled by CI in an independent consumer build.

## Pull requests and bug reports

Describe the change, its compatibility impact and the checks you actually ran.
For bugs, include a minimal reproducer, the library version or commit, the
server version, Android version and device, and sanitized logs (the `SocketIO`
logcat tag). Remove tokens, cookies, keys and personal data before sharing logs.
