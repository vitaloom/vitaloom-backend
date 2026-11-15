# Sleep F2 — Report and Insights API

**Status** Implemented in P1. Business policy is defined in [SLT-UC-009](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/SLT-UC-009_kham-pha-sleep-insights.md), [Sleep F2 rules](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/rules.md) and [OW-01–61](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/observation-window-decisions-2026-11-14.md). See BR-F2.1–3, .10–11, .18–19, .22, .38–39 and OW-01–05, OW-14, OW-44–48, OW-54/60.

The same contract also defines the authenticated Sleep Goal history operations for SLT-UC-008 and BR-F2.5/6/9/18.

## Sleep Goal history

Sleep owns account-scoped Goal history. All three endpoints require a Bearer access JWT and derive the account only from its subject. Responses use `Cache-Control: no-store`. Valid history is verified on read; malformed persisted history returns an error rather than an empty goal.

### `GET /api/sleep/goal`

Accepts optional `day=YYYY-MM-DD` to read the Goal effective on that day. The default is the current civil day in `Asia/Ho_Chi_Minh`. Dates after today, malformed dates, repeated `day` parameters and all other query parameters return `400`.

`200 OK` returns the account's history revision and the latest snapshot on or before the requested day. No earlier snapshot returns null minutes and effectiveDay. A removal snapshot returns null minutes and its effective day, preserving the preceding snapshots for earlier dates.

```json
{"minutes":480,"revision":2,"effectiveDay":"2026-11-14","verified":true}
```

### `PUT /api/sleep/goal`

Accepts exactly integer `minutes` and `revision`. Goal minutes must be 60–1440 inclusive in steps of 15. The current revision is required from a preceding confirmed read. On confirmation, the server records the new value effective today. A second confirmed change on the same day replaces that day's snapshot and advances the account history revision.

### `DELETE /api/sleep/goal?revision=<current revision>`

Writes a removal snapshot effective today. Earlier snapshots remain available to historical reads. The revision query parameter is required and must be the only query parameter.

Both mutations return `200 OK` with the same shape as GET and the incremented revision. Mutations with stale revisions return `409 SLEEP_GOAL_CONFLICT`. Invalid inputs return `400 INVALID_SLEEP_GOAL` or `400 INVALID_REQUEST`. Revision values are limited to JavaScript's safe integer range so Mobile can round-trip them.

The persistence document is keyed by account and uses one snapshot per effective day. Snapshot replacement and revision advancement are atomic within the document. Sleep exposes the effective-day lookup for server-owned Goal-dependent insight work. It does not calculate Statistics aggregates.

## Scope and ownership

Sleep F2 owns the Recent Sleep Report workflow (SLT-UC-009), the HTTP endpoint in `SleepReportInsightController`, and the response contract. P1 exposes the rolling report and A01 highlight. [Sleep Journal F1](../sleep-journal/contract-reconciliation.md) owns record CRUD and canonical MAIN facts.

The controller uses `SleepRecentReportService`. Its mapper adapts the result from `SleepRecentStatisticsService` into the Sleep API response. Statistics computes the rolling aggregates and A01 on read through the Sleep-owned `SleepMainReadQuery`, which reads the account's MAIN facts from MongoDB. [ADR-001](../../architecture/decisions/ADR-001-statistics-owns-aggregate-read-models.md)

## A02 and B04 Goal-dependent insights

`GET /api/sleep/insights` preserves the original A01/B01 result by default. The additive `catalog=goal` option returns A01, A02, B01 and B04 grouped by category. Within each category READY items precede locked items. READY items order by newest contributing day, then contributing sample size, then insight code. Locked items order by insight code. The P5A implementation leaves the Recent report's existing highlight selection unchanged.

`GET /api/sleep/insights/A02` and `GET /api/sleep/insights/B04` return current Detail using the same [authenticated historical Goal source](#sleep-goal-history) and Recent [D−6,D] period as the catalog read. Statistics evaluates the metrics from verified MAIN facts; Sleep reads and validates Goal history once per insight request. An empty verified Goal history is distinct from an unavailable or invalid history. The latter returns `UNVERIFIED_DATA` for A02 and B04 while verified A01 and B01 remain available.

A02 requires at least three distinct days with MAIN and an effective Goal. Its sample is `eligibleMainDays`; its evidence is `metGoalDays` and `eligibleMainDays`. It counts MAIN duration greater than or equal to that day's historical Goal. Days without MAIN or Goal are excluded from its denominator.

B04 requires at least three rated MAIN sessions and at least one tired MAIN whose duration meets the Goal effective on that day. Its sample reports `ratedMain` and `comparableRatedMain`. Evidence reports `exceptionCount`, the `ratedMain` denominator, and each exception's day, duration and effective Goal. A rated day without a Goal cannot be an exception. Both insights use self-reported data descriptively and make no health claim.

## `GET /api/sleep/recent`

- Requires an access JWT via `Authorization: Bearer <token>`. The verified JWT subject selects the account.
- The request has no body or query parameters. Any supplied query parameter, including `accountId`, `date`, `week` and `timeZone`, returns `400 INVALID_REQUEST` after authentication.
- Statistics computes D once per request from the application clock in `Asia/Ho_Chi_Minh`. The inclusive window is [D−6, D]. Every day is an ISO civil date (`YYYY-MM-DD`).
- The Sleep-owned provider queries canonical MongoDB `sleep_sessions` for the subject, MAIN type and inclusive bounds on stored `statistical_day`. RANGE contributes all stored minutes to its saved wake day in the session reference timezone. DURATION contributes its saved end day and minutes. The report has no bedtime field. [Sleep Journal F1 contract](../sleep-journal/contract-reconciliation.md)
- Responses carry `Cache-Control: no-store`. A fresh request observes confirmed Journal changes through the read path.

### Successful response

`200 OK` with `application/json`. All fields below are required, including explicit nulls. `days` contains exactly seven entries ordered oldest first, one per date. Statistics computes the aggregate values and A01 on read.

```json
{
  "period": {"start": "2026-11-08", "end": "2026-11-14", "timeZone": "Asia/Ho_Chi_Minh"},
  "dataStatus": "VERIFIED",
  "days": [
    {"day": "2026-11-08", "status": "RECORDED", "minutes": 420},
    {"day": "2026-11-09", "status": "NOT_RECORDED", "minutes": null},
    {"day": "2026-11-10", "status": "NOT_RECORDED", "minutes": null},
    {"day": "2026-11-11", "status": "RECORDED", "minutes": 450},
    {"day": "2026-11-12", "status": "NOT_RECORDED", "minutes": null},
    {"day": "2026-11-13", "status": "NOT_RECORDED", "minutes": null},
    {"day": "2026-11-14", "status": "RECORDED", "minutes": 451}
  ],
  "recordedDays": 3,
  "coverageDenominator": 7,
  "averageMinutesPerRecordedDay": 440,
  "insights": [{"code": "A01", "status": "READY", "sampleDays": 3, "denominatorDays": 7}]
}
```

| `dataStatus` | Entries in `days` | `recordedDays` / `coverageDenominator` / `averageMinutesPerRecordedDay` | `insights` and display |
| --- | --- | --- | --- |
| `NO_DATA` | Seven `NOT_RECORDED` with `minutes: null` | `0` / `7` / `null` | `[]`. Show guided empty state linking to record Main Sleep. |
| `VERIFIED` with 1–2 MAIN days | `RECORDED` minutes 1–1440 on recorded days. Other days are `NOT_RECORDED` with null. | `1` or `2` / `7` / computed integer | `[]`. A01 is semantically `INSUFFICIENT_DATA`. Show verified metrics and the Insights empty state. |
| `VERIFIED` with 3–7 MAIN days | Same cell rules | `3`–`7` / `7` / computed integer | One A01 READY highlight. `sampleDays` matches `recordedDays`. `denominatorDays` is 7. A01 describes recording coverage. |
| `UNVERIFIED_DATA` | `RECORDED` only for independently verified individual facts. Other cells are `UNVERIFIED` with null minutes. | `null` / `7` / `null` | `[]`. Show a history-verification notice and any verified individual facts. |

A successful complete read of canonical MAIN data with one valid record per returned day verifies the window at read time, including today so far. Verified empty days have null minutes. The average is the sum of recorded minutes divided by `recordedDays`, rounded to the nearest whole minute with halves rounded up. A01 uses the distinct verified MAIN day count over seven. `INS-S01` is its legacy alias and is absent from the payload.

`UNVERIFIED_DATA` represents a completed request where the service can establish that history is semantically incomplete, for example a malformed MAIN fact detected within the window. Withhold any disputed day and all window-level aggregates and insights. `coverageDenominator: 7` describes the window length. Failure to execute or decode the Mongo read is a request error and returns no report.

The `highlights` array adds Recent Highlight Cards while preserving the legacy `insights` array and its A01 fields. It is always present and may be empty. Each entry contains `code`, `status`, `title` and `summary`; only currently supported READY A01/B01 evaluations are eligible. Results are deduplicated by code and capped at three. Ranking uses the freshest contributing day descending, prefers B01 over A01 for the same day, then orders by larger contributing sample and stable code. B01 contributes only rated MAIN days; A01 contributes recorded MAIN days. Locked, insufficient and unverified evaluations are omitted. The period, JWT subject and no-store behavior are the same as the report.

### Request errors

Use [shared Problem Details](../../api/api-contract.md#generic-error-response) with `Content-Type: application/problem+json` and a top-level `code`.

| HTTP | Code | Condition |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Query parameters supplied to `GET /api/sleep/recent` |
| 401 | `UNAUTHORIZED` | Missing, invalid or expired access JWT |
| 403 | `FORBIDDEN` | Authenticated request denied by shared security policy, when applicable |
| 500 | `INTERNAL_ERROR` | Unexpected failure, including a failed Mongo read |

A valid subject with an empty Journal receives `200 NO_DATA`. A request cannot select a different account. Goal history is available through the [Sleep Goal endpoint](#sleep-goal-history).

## `GET /api/sleep/insights`

Requires Bearer access JWT; the JWT subject selects the account. The optional `catalog=goal` selects the [Goal catalog](#a02-and-b04-goal-dependent-insights). Other parameters or values return `400 INVALID_REQUEST`. The response is `Cache-Control: no-store`. Statistics evaluates the current `[D−6,D]` period in `Asia/Ho_Chi_Minh` on each request. The default response returns A01 and B01 in category order A then B. Deferred C entries are omitted.

The response uses `{period,items}`. Every item has `code`, `category` (`A` or `B`), `status`, `title`, `highlight`, and `locked`. READY entries may also carry `summary`, `sample`, `evidence`, `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Locked entries carry only status/title metadata in addition to code/category/locked; they do not expose sample progress or evidence.

For B01, all confirmed, valid MAIN sessions in the current window are counted. `sample.ratedMain` is the denominator and `sample.mainWithoutFeeling` reports MAIN without a nonblank feeling separately. `evidence` reports count, denominator and percentage for COMFORTABLE, NORMAL and TIRED. READY requires at least three rated MAIN. Feeling is self-reported and the output is descriptive; it makes no causal or health claim. The deterministic `evidenceVersion` is a SHA-256 fingerprint of the source facts, and `sourceRefs` contains source session identifiers. A fresh read reflects Journal changes.

Status mapping is `NO_DATA` for zero MAIN, `INSUFFICIENT_DATA` when there are MAIN but fewer than three rated MAIN, and `UNVERIFIED_DATA` when any in-window fact prevents verifying the full history. No distribution or conclusion is returned for non-READY B01. A01 uses the existing recording-coverage rule and source path.

## `GET /api/sleep/insights/{code}`

Requires Bearer access JWT and accepts the currently supported codes `A01`, `A02`, `B01`, and `B04`. It accepts no query parameters; the detail period is always the current `[D−6,D]` and any query is `400 INVALID_REQUEST`. An unsupported code returns `404 INSIGHT_NOT_FOUND`. Responses are no-store and refetch current evidence, including the effect of Journal edits and applicable Goal changes.

The JSON fields are `code`, `category`, `status`, `period`, `title`, optional `summary`, `sample`, and `evidence`, plus `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Non-READY detail includes the current status with empty source/limitation lists and no stale evidence. Shared authentication and Problem Details conventions apply.

## Sleep Story preview

All three requests below require an access Bearer JWT, select the account by its subject, accept no query parameters and return `Cache-Control: no-store` on success.

- `GET /api/sleep/ai/consent` returns `{enabled,available}` as booleans. Missing consent defaults to OFF. `available` indicates whether the dev/test provider is active.
- `PUT /api/sleep/ai/consent` accepts exactly `{"enabled":true}` or `{"enabled":false}` and returns the same shape as GET. It stores one preference per account in `sleep_ai_consent`, keyed by the JWT subject. Revocation is accepted even when the provider is disabled. Enabling while disabled returns `409 SLEEP_STORY_DISABLED`. An unsupported JSON shape or query parameters return `400 INVALID_REQUEST`. Request decoding follows the shared Problem Details conventions.
- `GET /api/sleep/recent/story` returns `{status}` for `DISABLED`, `CONSENT_REQUIRED` or `INELIGIBLE`. A `STORY` or `FALLBACK` result includes `headline` and one or two `observations`. Each observation has `insightCode`, `text`, `dataContext` and `evidenceFingerprint` matching the evidence version of that insight's current Detail. Clients can open that Detail by code and fetch it again.
- `POST /api/sleep/ai/explanations` accepts `{"insightCode":"B01","evidenceVersion":"..."}` with Bearer authentication. Supported codes are A01 and B01. The optional version is a hint. The server uses current READY evidence and rechecks it and consent before returning. Status `EXPLANATION` or `FALLBACK` includes `observation`, `evidenceBasis`, `limitations` and `evidenceFingerprint`. Other statuses contain only `status` (`DISABLED`, `CONSENT_REQUIRED`, `INELIGIBLE`, `STALE`). Responses use `Cache-Control: no-store`. The deterministic provider is limited to dev/test. Redis, cooldown and a real provider are deferred to P6.

The service needs current verified Published READY evidence and at least one insight beyond A01 (B01 in P3). It gives the local fake provider limited counts, the observation period, version and fingerprint, with no journal rows, account identifiers or source references. The server validates Vietnamese sentences against evidence-derived templates and checks the 120-word budget. After generation, it reads consent and the published candidate pool again. A consent revision change suppresses all output. Invalid or stale provider output can yield a deterministic fallback only when the current pool is eligible and consent still matches. If eligibility has been lost, the response has no content.

P3 is a development/test preview. It requires the Spring profile `dev` or `test` and `VITALOOM_SLEEP_STORY_ENABLED=true`. Explicit `prod` or `production` profiles disable the provider. Production readiness, a real provider, Redis, cooldown, timeout, retry, single-flight and operational rate limiting are deferred.
