# IB TWS API (vendored, unmodified)

IB's official Java client, copied **unchanged** from IB's API package.

| | |
|---|---|
| Version | 10.50.02 ("Stable") |
| Source | <https://interactivebrokers.github.io/downloads/twsapi_macunix.1050.02.zip> |
| SHA-256 of that zip | `673129e5cba58c4d77bc40647265f84ea42f605eccf88fa4c1221d62d12454f3` |
| Copied | `IBJts/source/JavaClient/com/**` → `src/main/java/com/**` (295 files) |
| Licence | GNU GPL v3 or later (`LICENSE`); third-party notices in `NOTICE` and `THIRD_PARTY_LICENSES/` |
| Copyright | Interactive Brokers LLC |

Only `pom.xml`, this README and `NOTICE` (from the same zip) were added. Built with `--release 17` and depends on
`com.google.protobuf:protobuf-java:4.29.5`, the version IB ships with this release.

## Upgrading

1. Download the new zip from <https://interactivebrokers.github.io/>, record its SHA-256 here.
2. Replace `src/main/java/com/**` with the zip's `IBJts/source/JavaClient/com/**`; update `LICENSE`, `NOTICE`,
   `THIRD_PARTY_LICENSES/` and the protobuf version IB lists in `NOTICE`.
3. `mvn test` at the repo root.
