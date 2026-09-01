# Live API validation

Last validated: 2026-09-01 against one private athlete account. This document
contains aggregate results only. The account export, identifiers, workout values,
and coaching text are not stored in the repository.

## Authentication

- `POST https://apis.trainheroic.com/auth` returned `id`, `role`, `scope`, and
  `session_id`.
- The response contained no refresh token, expiry, or TTL. SyncHeroic therefore
  retains the password in Android Keystore-protected encrypted storage so it can
  authenticate again after a session expires.

## History completeness

- The history endpoint was queried from 2000 through the validation date in 55
  non-overlapping 180-day windows.
- It returned 423 rows and 423 unique workout IDs, with no boundary duplicates.
- The largest window contained 155 rows, providing no evidence of a fixed page
  truncation at the observed volume.
- 124 workouts contained at least one performed set. The profile summary reported
  146 sessions, confirming that `sessions_count` uses a broader definition and
  must not be used as a completeness equality check.

## Session time

- All 124 logged workouts had `timestamp_started`.
- 115 also had `timestamp_completed` and a positive duration; 114 were shorter
  than 24 hours.
- Plausible durations ranged from 1 to 116 minutes, with a median of 62 minutes.
- SyncHeroic uses a positive server start/end pair shorter than or equal to 24
  hours as the first-choice session envelope. Incomplete or invalid pairs continue
  through wearable borrowing, grace-period holding, and deterministic synthesis.

## Volume

- A conservative performed-set calculation found 533 repetition/weight pairs and
  250,670 units of volume.
- The profile reported 256,615, approximately 2.3% more. Profile volume therefore
  has different or additional semantics and is retained only as a drift signal;
  SyncHeroic does not write or display a computed volume aggregate.

## Physical device validation

Validated on a Google Pixel 9 Pro XL running Android 17 (API 37) with the
system Health Connect controller. The device serial, build fingerprint, account
details, workout dates, titles, notes, and identifiers were not recorded.

- The isolated debug application installed, cold-launched, authenticated, and
  stored its credential envelope without an application error.
- Health Connect granted exercise read/write, history-read, and background-read
  access through the system permission flow.
- A 30-day preview proposed 9 inserts and 17 skips. Applying that exact preview
  wrote 9 sessions; repeating the preview produced 9 unchanged records, 17
  skips, and no inserts or updates.
- Health Connect displayed exactly one SyncHeroic entry on each of the 9 expected
  days. Each coexisted with an existing Health-origin entry, and days containing
  only Health-origin data remained unchanged.
- Imported titles, times, and notes rendered in Health Connect at the lengths
  present in the observed corpus.
- The schema report contained 260 observations across 10 unknown JSON paths and
  zero unparsed performed values. Unknown paths are reported rather than mapped
  speculatively.

### Accepted v0.1.0 limitations

- Update and grace-period hold actions were not induced against live upstream
  data; their deterministic planner behavior is covered by automated tests.
- Destructive deletion and delete-after-reinstall flows were not exercised on
  the personal device.
- Notes rendered correctly at observed lengths, but Health Connect's practical
  maximum notes length was not measured with a synthetic record.
- Long-term wearable sync lag was not measured. The observed sessions matched
  existing Health-origin sessions without modifying or removing them, and the
  grace period remains user-adjustable.

## Validation scope

These findings derive from one private account and its programming history.
Additional account shapes can broaden compatibility evidence over time, but are
not required for a release. Strict parsing, preview, and fail-closed behavior
remain the safeguards for data shapes the reference corpus has not demonstrated.

Maintainers can reproduce the shape-only probe with:

```shell
node tools/live-validate.mjs .private/trainheroic.env
```
