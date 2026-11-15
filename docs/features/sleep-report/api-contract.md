# Sleep F2 — Report and Insights API

**Status** Implemented in P1. Business policy is defined in [SLT-UC-009](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/SLT-UC-009_kham-pha-sleep-insights.md), [Sleep F2 rules](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/rules.md) and [OW-01–61](https://github.com/vitaloom/vitaloom-business-docs-and-tools/blob/ad9711e01dd486ae2e7b35275cbcacbf61cf65ed/docs/usecase_spec/sleep-tracking/f2-sleep-goals-insights/observation-window-decisions-2026-11-14.md). See BR-F2.1–3, .10–11, .18–19, .22, .38–39 and OW-01–05, OW-14, OW-44–48, OW-54/60.

## Scope and ownership

Sleep F2 owns the Recent Sleep Report workflow (SLT-UC-009), the HTTP endpoint in `SleepReportInsightController`, and the response contract. P1 exposes the rolling report and A01 highlight. [Sleep Journal F1](../sleep-journal/contract-reconciliation.md) owns record CRUD and canonical MAIN facts.

The controller uses `SleepRecentReportService`. Its mapper adapts the result from `SleepRecentStatisticsService` into the Sleep API response. Statistics computes the rolling aggregates and A01 on read through the Sleep-owned `SleepMainReadQuery`, which reads the account's MAIN facts from MongoDB. [ADR-001](../../architecture/decisions/ADR-001-statistics-owns-aggregate-read-models.md)

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

### Request errors

Use [shared Problem Details](../../api/api-contract.md#generic-error-response) with `Content-Type: application/problem+json` and a top-level `code`.

| HTTP | Code | Condition |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Query parameters supplied |
| 401 | `UNAUTHORIZED` | Missing, invalid or expired access JWT |
| 403 | `FORBIDDEN` | Authenticated request denied by shared security policy, when applicable |
| 500 | `INTERNAL_ERROR` | Unexpected failure, including a failed Mongo read |

A valid subject with an empty Journal receives `200 NO_DATA`. A request cannot select a different account. Goal history, consent and AI remain later delivery phases.

## `GET /api/sleep/insights`

Requires Bearer access JWT; the JWT subject selects the account. It accepts no query parameters and returns `400 INVALID_REQUEST` if any are supplied. The response is `Cache-Control: no-store`. Statistics evaluates the current `[D−6,D]` period in `Asia/Ho_Chi_Minh` on each request. Only supported A01 and B01 are returned, in category order A then B. Other catalog entries and deferred C entries are omitted.

The response uses `{period,items}`. Every item has `code`, `category` (`A` or `B`), `status`, `title`, `highlight`, and `locked`. READY entries may also carry `summary`, `sample`, `evidence`, `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Locked entries carry only status/title metadata in addition to code/category/locked; they do not expose sample progress or evidence.

For B01, all confirmed, valid MAIN sessions in the current window are counted. `sample.ratedMain` is the denominator and `sample.mainWithoutFeeling` reports MAIN without a nonblank feeling separately. `evidence` reports count, denominator and percentage for COMFORTABLE, NORMAL and TIRED. READY requires at least three rated MAIN. Feeling is self-reported and the output is descriptive; it makes no causal or health claim. The deterministic `evidenceVersion` is a SHA-256 fingerprint of the source facts, and `sourceRefs` contains source session identifiers. A fresh read reflects Journal changes.

Status mapping is `NO_DATA` for zero MAIN, `INSUFFICIENT_DATA` when there are MAIN but fewer than three rated MAIN, and `UNVERIFIED_DATA` when any in-window fact prevents verifying the full history. No distribution or conclusion is returned for non-READY B01. A01 uses the existing recording-coverage rule and source path.

## `GET /api/sleep/insights/{code}`

Requires Bearer access JWT and accepts only currently supported codes `A01` and `B01`. It accepts no query parameters; the detail period is always the current `[D−6,D]` and any query is `400 INVALID_REQUEST`. An unsupported code returns `404 INSIGHT_NOT_FOUND`. Responses are no-store and refetch the current evidence; clients must not treat prior detail evidence as current after Journal changes.

The JSON fields are `code`, `category`, `status`, `period`, `title`, optional `summary`, `sample`, and `evidence`, plus `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Non-READY detail includes the current status with empty source/limitation lists and no stale evidence. Shared authentication and Problem Details conventions apply.
