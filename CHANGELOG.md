# Changelog

All notable changes will be documented here. This project follows Semantic
Versioning.

## 0.2.0 - 2026-09-01

- Added opt-in 15-minute TrainHeroic syncing during a configurable daily workout
  window, with a 12:00–13:30 local-time default.
- Added throttled foreground refreshes and retained the daily eight-day
  reconciliation fallback.
- Prevented overlapping automatic syncs, reduced frequent sync reads to two days,
  and avoided retry storms for non-transient HTTP failures.
- Documented that Android schedules background work on a best-effort basis and may
  delay execution beyond the configured interval.

## 0.1.0 - 2026-09-01

- Initial open-source Android implementation.
- Previewed and imported TrainHeroic strength sessions into Health Connect with
  deterministic deduplication and wearable-session alignment.
- Added encrypted on-device credential storage, background sync, deletion tools,
  aggregate-only schema reporting, CI, and signed release automation.
