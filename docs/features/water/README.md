# Water Intake Tracking MVP

This feature stores each drinking action as a MongoDB WaterLog owned by the authenticated account. The mobile Water Landing and Detailed Entry screens create logs; Water Landing and Progress read daily and weekly totals from the backend. Totals are derived from stored logs, with a fixed daily goal of 2,000 ml.

The MVP provides three routes: `GET /api/water/daily-summary`, `GET /api/water/weekly-progress`, and `POST /api/water/logs`. GET requests include an IANA `timeZone` so local calendar dates are interpreted in the user's device timezone. The timestamp sent to POST includes an offset or `Z` and represents an absolute instant.

See [API contract](api-contract.md), [implementation report](implementation-report.md), [verification report](verification-report.md), and [remaining work](remaining-work.md).

Goals management, log edit/delete, reminders, offline synchronization, and a retry idempotency key are outside this MVP.
