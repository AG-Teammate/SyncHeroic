# SyncHeroic

SyncHeroic is an unofficial, open-source Android app that copies your logged
TrainHeroic workout history into Health Connect. It runs entirely on your phone:
there is no SyncHeroic backend, account, telemetry, advertising, or analytics.

> SyncHeroic is not affiliated with, endorsed by, or connected to TrainHeroic.
> It uses an undocumented API that may change or stop working without notice.

## What it writes

SyncHeroic writes one strength-training exercise session for each logged workout.
It preserves performed repetitions, loads, and programming text as readable
notes. It deliberately does not write calories, heart rate, distance, steps, or
power. A wearable's record remains untouched and should have higher priority in
Health Connect.

Before any import, SyncHeroic previews what it will insert, update, hold, or skip.
Everything it writes can be removed from the Data and privacy screen.

## Status

The project is under active development and is not yet a validated 1.0 release.
The behavior described in [the design](docs/DESIGN.md) has been validated against
one private account; see the aggregate-only [validation report](docs/VALIDATION.md).
Additional account and programming variations are welcome as ongoing compatibility
evidence, but are not a release requirement. The v0.1.0 flow was also exercised on
a physical Android 17 device; untested destructive paths are documented as accepted
limitations rather than implied successes.

## Build

Requirements: JDK 21 and an Android SDK containing API 36.

```shell
./gradlew check :app:assembleDebug
```

No private fixture or credentials are required. Optional maintainer-only data
belongs under `.private/`, which Git ignores.

## Releases and verification

Release APKs are published only through GitHub Releases. Each release includes a
SHA-256 checksum and the signing certificate fingerprint so update continuity can
be verified. Never install an APK whose checksum or certificate differs from the
release notes.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
