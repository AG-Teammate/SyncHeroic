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

## Remaining release gate

These findings still derive from one account. A second private account with a
different program and unit mix remains required before 1.0.

Maintainers can reproduce the shape-only probe with:

```shell
node tools/live-validate.mjs .private/trainheroic.env
```

