# Roadmap to 1.0

SyncHeroic 1.0 should mean that its data contract, upgrade behavior, and core
device workflows are stable. These tasks do not require a second TrainHeroic
account or Google Play distribution.

## TrainHeroic compatibility

- [ ] Classify the 10 currently unknown API paths and eliminate expected-path
  noise from the in-app drift warning.
- [ ] Add aggregate-safe regression fixtures for every supported live response
  shape.
- [ ] Define and document how unsupported API changes fail closed, recover, and
  are reported without private workout content.
- [ ] Keep endpoint and exercise mappings independently updateable without
  weakening transport or parsing validation.

## Health Connect lifecycle

- [ ] Exercise insert, unchanged, update, grace-period hold, and skip paths on a
  physical device.
- [ ] Verify delete-all behavior removes only SyncHeroic-owned records.
- [ ] Verify delete-after-reinstall behavior with the same application identity.
- [ ] Verify an upgrade from the production-signed v0.1.0 APK preserves encrypted
  credentials, settings, provenance, and existing Health Connect records.
- [ ] Measure Health Connect's practical notes-length limit and implement an
  explicit truncation or failure policy.
- [ ] Observe wearable matching over a representative period and revisit the
  default grace period using aggregate-only evidence.

## Background reliability

- [ ] Exercise WorkManager sync under idle, battery-saver, reboot, offline, and
  expired-session conditions.
- [ ] Confirm retries remain idempotent and surface actionable status without
  duplicate Health Connect records.
- [ ] Document background-permission behavior across supported Android versions.

## Platform and dependencies

- [ ] Merge independently compatible, green dependency updates after rebasing
  each one onto current `main`.
- [ ] Plan a coordinated compile SDK 37 and Android Gradle Plugin 9.1+ migration
  before adopting dependencies that require them.
- [ ] Re-run release signing, shrinking, lint, and physical-device smoke tests
  after the platform migration.

## Stability and release policy

- [ ] Triage v0.x user reports and resolve all known data-integrity defects.
- [ ] Document backward-compatible settings and provenance migrations.
- [ ] Define the supported Android and Health Connect version matrix.
- [ ] Confirm production signing-key recovery and certificate continuity from an
  independent backup.
- [ ] Publish a 1.0 release candidate and verify installation plus upgrade from
  the latest v0.x release before tagging v1.0.0.

## Optional distribution work

- [ ] Register `app.syncheroic` and prepare store metadata if Google Play
  distribution is desired. This is not a v1.0 requirement.
