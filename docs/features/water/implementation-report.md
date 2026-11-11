# Water Intake MVP — Implementation Report

## Scope and architecture

The mobile Water screens now use `ApiWaterRepository` through the existing `WaterRepository` seam. It calls the shared authenticated `apiRequest` client so Water requests use the current Bearer JWT and single-flight refresh flow. A successful POST increments the repository revision, causing the daily and weekly hooks to reload. The hooks key reads by date, week, and revision and ignore stale effect results.

The backend exposes three `/api/water/**` routes protected by Spring Security. The JWT subject is the MongoDB User ID; the controller passes that authenticated ID to the Water service. `WaterLog` records are persisted in the `water_logs` MongoDB collection with BSON fields `user_id`, `amount_ml`, and UTC `logged_at`; Java properties remain `userId`, `amountMl`, and `loggedAt`. Repository reads filter by authenticated user ID and an inclusive start/exclusive end interval, ordering logs newest first. The service derives daily and weekly totals from stored logs.

GET requests include the device's IANA timezone. The backend uses local calendar boundaries in that zone, including historical dates and daylight saving changes. POST accepts only an absolute timestamp and converts it to an `Instant`. The fixed goal is 2,000 ml/day. The API returns an unclamped daily percent; only the FE visual ring is capped. Source metadata remains FE-only, so historical logs cannot identify whether they were created via Quick Add or Detailed Entry.

## Contract decisions

The implemented routes use `/api/water/daily-summary`, `/api/water/weekly-progress`, and `/api/water/logs`. The older backend contract's proposed `/api/water-intakes` route and client `intakeId` were unimplemented and are superseded for this MVP. The new create API has no server idempotency key; the FE prevents double taps and does not automatically replay uncertain POST requests. Other metric routes and their `Asia/Ho_Chi_Minh` statistics timezone remain unchanged.

## Source changes

Backend:

- `src/main/java/com/vitaloom/backend/config/SecurityConfig.java` — protect `/api/water/**` before the broad public `/api/**` matcher.
- `src/main/java/com/vitaloom/backend/water/` — controller, request/response DTOs, service, repository, document model, and error translation.
- `src/test/java/com/vitaloom/backend/water/service/` — service tests for aggregation, timezone, isolation, validation, and strict JSON integer amounts. Live HTTP/security checks are documented in the verification report.

Frontend:

- `src/features/water/services/api-water.repository.ts` — authenticated HTTP adapter and DTO mapping.
- `src/features/water/services/water.repository.ts` — select API repository instead of mock.
- `src/features/water/screens/water-landing.tsx` and `water-detail-entry.tsx` — surface server errors and remove sample-data copy.
- `tests/water-api-repository.test.cjs` — adapter request, response, error, and timezone behavior.

Documentation: backend `README.md`, `docs/api/api-contract.md`, and this `docs/features/water/` directory; frontend `README.md` and `docs/api/water-intake-proposal.md`.

## Integration outcome

Water Landing requests the current daily summary. Quick Add posts only when Add Log is pressed, then reloads progress and latest-five logs. Detailed Entry posts the chosen past instant and retains the form on failure. Progress requests a seven-day week and a selected day's summary. The in-memory mock remains in source as a test/development fixture but is no longer selected at runtime. See [verification report](verification-report.md) for checks actually executed and [remaining work](remaining-work.md) for limits.

The first live backend start exposed a missing Spring constructor selection; the runtime constructor is now marked for injection. The first Mongo-backed GET exposed a Spring Data derived-query limitation for two predicates on `loggedAt`; the repository now uses an explicit inclusive-start/exclusive-end Mongo query with descending time sort. Both issues were fixed before the passing live verification.

Live POST checks also exposed Jackson's default coercion of fractional and string JSON amounts into `Integer`. The request DTO now checks the raw JSON node type before passing an integer to the service; fractional, string, and boolean amounts return 400.
