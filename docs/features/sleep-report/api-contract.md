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

`GET /api/sleep/insights` preserves the original A01/B01 result by default. The additive `catalog=goal` option returns A01, A02, B01 and B04. The additive `catalog=trend` option returns A01, A02, A03, B01, B03 and B04. Both catalog options retain the current `[D−6,D]` list period and the existing category ordering. Within each category READY items precede locked items. READY items order by newest contributing day, then contributing sample size, then insight code. Locked items order by insight code. The catalog options do not change Recent report highlight selection.

`GET /api/sleep/insights/A02` and `GET /api/sleep/insights/B04` return current Detail using the same [authenticated historical Goal source](#sleep-goal-history) and Recent [D−6,D] period as the catalog read. Statistics evaluates the metrics from verified MAIN facts; Sleep reads and validates Goal history once per insight request. An empty verified Goal history is distinct from an unavailable or invalid history. The latter returns `UNVERIFIED_DATA` for A02 and B04 while verified A01 and B01 remain available.

A02 requires at least three distinct days with MAIN and an effective Goal. Its sample is `eligibleMainDays`; its evidence is `metGoalDays` and `eligibleMainDays`. It counts MAIN duration greater than or equal to that day's historical Goal. Days without MAIN or Goal are excluded from its denominator.

B04 requires at least three rated MAIN sessions and at least one tired MAIN whose duration meets the Goal effective on that day. Its sample reports `ratedMain` and `comparableRatedMain`. Evidence reports `exceptionCount`, the `ratedMain` denominator, and each exception's day, duration and effective Goal. A rated day without a Goal cannot be an exception. Both insights use self-reported data descriptively and make no health claim.

## A03 and B03 completed-window trends

`GET /api/sleep/insights?catalog=trend` publishes A03 and B03 alongside the existing insights. `GET /api/sleep/insights/A03` and `/B03` return the same current evidence versions as the corresponding catalog items. Their Detail `period` is the completed recent trend window `[D−7,D−1]`; evidence also includes the baseline `[D−14,D−8]`. Both periods contain seven consecutive civil days in `Asia/Ho_Chi_Minh`. Today is excluded even when it has a confirmed MAIN.

A03 independently reads verified MAIN duration facts in both periods. READY requires at least three distinct recorded MAIN days in each. Its sample has `baselineMainDays` and `recentMainDays`. Evidence has `baseline` and `recent` objects with `period` and `meanMinutes`, plus signed `deltaMinutes` (recent minus baseline). Means are integer minutes rounded half up. A missing, unverified or under-threshold baseline prevents READY publication.

B03 independently reads verified MAIN facts and requires at least three rated MAIN in each period. Unrated MAIN is excluded from each distribution denominator and reported as `mainWithoutFeeling`. Its sample has `baselineRatedMain`, `recentRatedMain`, `baselineMainWithoutFeeling` and `recentMainWithoutFeeling`. Evidence has `baseline` and `recent` objects with `period`, `ratedMain`, `mainWithoutFeeling` and `comfortable`, `normal` and `tired` `{count,denominator,percentage}` distributions. `deltaPercentagePoints` reports signed recent-minus-baseline percentage-point changes for all three categories, rounded to two decimal places from the unrounded shares. Both insights are descriptive and make no health claim. Non-READY responses omit sample and evidence. A trend is `NO_DATA` when neither period has MAIN, `INSUFFICIENT_DATA` when either threshold is unmet, and `UNVERIFIED_DATA` when either period cannot be verified.

## A04, A05 and B02 in the full catalog

`catalog=full` returns nine A/B items in the same category and eligibility order. Only this catalog adds top-level `timeZones`, a sorted list of distinct reference IANA zones found on verified, valid MAIN RANGE records in the account's current rolling window. The list is empty when no zone is available or the RANGE history cannot be verified. An optional single, case-sensitive `timeZone` query selects which records can contribute to A04. The service never selects a zone automatically. The full catalog, full Recent and A04 Detail accept the same zone input. Invalid or repeated zone parameters return `400 INVALID_REQUEST`. A valid zone with insufficient matching data gives a locked A04.

- A05 uses the current `[D−6,D]` window. READY requires at least three recorded weekdays and two recorded weekend days. Both RANGE and DURATION MAIN count, including a confirmed MAIN for today. Evidence contains `weekday` and `weekend` objects, each with `days` and `meanMinutes`, plus `deltaMinutes` (weekday minus weekend). Sample fields are `weekdayDays` and `weekendDays`.
- B02 uses the same rolling window and requires at least three Comfortable and three Tired MAIN. Normal and unrated MAIN are excluded from the two groups. Evidence contains `comfortable` and `tired` objects, each with `days` and `meanMinutes`, plus `deltaMinutes` (Comfortable minus Tired). Sample fields are `comfortableDays` and `tiredDays`. Comparison means are rounded half up to whole minutes. The signed difference uses those published means. Both comparisons describe observations.
- A04 requires at least three verified MAIN RANGE days in the selected zone. The rolling period still follows the report calendar and stored statistical days. A transition within those civil dates in the selected zone locks A04 for the entire window. A session crossing a transition outside the window is excluded from the sample. A04 uses stored instants and the stored reference zone. The sample field is `eligibleRangeDays`; evidence includes `timeZone` and `bedtime` and `wake` objects with `minMinute`, `maxMinute` and `spanMinutes`. Values are minutes relative to midnight on the stored wake day. For example, bedtime at 23:00 on the previous day is −60. DURATION has no timing contribution.

Locked entries omit evidence and sample. Story and Explanation support these published codes as described below; A04 still requires an explicitly selected reference zone.

## `GET /api/sleep/recent`

- Requires an access JWT via `Authorization: Bearer <token>`. The verified JWT subject selects the account.
- The request has no body. The default request has no query parameters. Opt into expanded highlight candidates with `catalog=full`. Full Recent also accepts the optional A04 `timeZone` described above. Unsupported, empty or repeated query values return `400 INVALID_REQUEST` after authentication.
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

The `highlights` array preserves the legacy `insights` array and its A01 fields. It is always present and may be empty. Each entry contains `code`, `status`, `title` and `summary`. Default candidates are A01/B01. Full Recent considers all nine READY A/B evaluations, each with its own observation period and eligibility. Results are deduplicated by code and capped at three. Ranking uses freshest contributing day descending, B01's coverage tie-break, larger contributing sample and stable code. B01 contributes only rated MAIN days and A01 contributes recorded MAIN days. Locked, insufficient and unverified evaluations are omitted. The JWT subject and no-store behavior are the same as the report.

### Request errors

Use [shared Problem Details](../../api/api-contract.md#generic-error-response) with `Content-Type: application/problem+json` and a top-level `code`.

| HTTP | Code | Condition |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | Unsupported, invalid or repeated query parameters |
| 401 | `UNAUTHORIZED` | Missing, invalid or expired access JWT |
| 403 | `FORBIDDEN` | Authenticated request denied by shared security policy, when applicable |
| 500 | `INTERNAL_ERROR` | Unexpected failure, including a failed Mongo read |

A valid subject with an empty Journal receives `200 NO_DATA`. A request cannot select a different account. Goal history is available through the [Sleep Goal endpoint](#sleep-goal-history).

## `GET /api/sleep/insights`

Requires Bearer access JWT. The JWT subject selects the account. The optional `catalog` values are [`goal`](#a02-and-b04-goal-dependent-insights), [`trend`](#a03-and-b03-completed-window-trends) and [`full`](#a04-a05-and-b02-in-the-full-catalog). Only the full option accepts `timeZone`. The response is `Cache-Control: no-store`. The list period is `[D−6,D]` in `Asia/Ho_Chi_Minh`. The default response returns A01 and B01 in category order A then B. Deferred C entries are omitted.

The response uses `{period,items}`. Every item has `code`, `category` (`A` or `B`), `status`, `title`, `highlight`, and `locked`. READY entries may also carry `summary`, `sample`, `evidence`, `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Locked entries carry only status/title metadata in addition to code/category/locked; they do not expose sample progress or evidence.

For B01, all confirmed, valid MAIN sessions in the current window are counted. `sample.ratedMain` is the denominator and `sample.mainWithoutFeeling` reports MAIN without a nonblank feeling separately. `evidence` reports count, denominator and percentage for COMFORTABLE, NORMAL and TIRED. READY requires at least three rated MAIN. Feeling is self-reported and the output is descriptive; it makes no causal or health claim. The deterministic `evidenceVersion` is a SHA-256 fingerprint of the source facts, and `sourceRefs` contains source session identifiers. A fresh read reflects Journal changes.

Status mapping is `NO_DATA` for zero MAIN, `INSUFFICIENT_DATA` when there are MAIN but fewer than three rated MAIN, and `UNVERIFIED_DATA` when any in-window fact prevents verifying the full history. No distribution or conclusion is returned for non-READY B01. A01 uses the existing recording-coverage rule and source path.

## `GET /api/sleep/insights/{code}`

Requires Bearer access JWT and accepts all nine A01–A05 and B01–B04 codes. A04 accepts the optional `timeZone` query. A03/B03 use completed `[D−7,D−1]`. The other codes use current `[D−6,D]`. An unsupported code returns `404 INSIGHT_NOT_FOUND`. Responses are no-store and refetch current evidence, including the effect of Journal edits and applicable Goal changes.

The JSON fields are `code`, `category`, `status`, `period`, `title`, optional `summary`, `sample`, and `evidence`, plus `sourceRefs`, `limitations`, `policyVersion`, and `evidenceVersion`. Non-READY detail includes the current status with empty source/limitation lists and no stale evidence. Shared authentication and Problem Details conventions apply.

READY A01 and A02 Detail add `evidence.days`, an array of exactly seven `{day,status}` objects ordered oldest first for the Detail `period`. `day` is an ISO civil date. A01 statuses are `RECORDED` and `NOT_RECORDED`; they are derived from the same verified MAIN window as the coverage aggregate. A02 statuses are `MET`, `MISSED`, `NO_MAIN`, and `NO_GOAL`; they use the same verified MAIN insight and historical Goal windows as the aggregate. `NO_MAIN` takes precedence when a date has a Goal but no MAIN. List/catalog items and all non-READY Detail responses omit `evidence.days`; existing fields and the A01/B01 catalog filters are unchanged.

A01's `evidenceVersion` includes its period and the MAIN facts used by the aggregate. It therefore matches the A01 List item and Story/AI evidence computed for the same period and facts, and changes when the reporting period changes. A02's `evidenceVersion` includes all seven dated status classifications, including dates outside its eligible sample, plus the existing eligible MAIN source values and effective Goals. Both calendars are calculated from the already-read windows; they do not trigger additional reads. Source references remain aggregate-level identifiers and are not added to individual day entries.

## Sleep Story preview

All requests below require an access Bearer JWT, select the account by its subject and return `Cache-Control: no-store` on success. Consent and Explanation accept no query parameters. Story accepts only an optional single `timeZone` with the same IANA validation as A04 Detail.

- `GET /api/sleep/ai/consent` returns `{enabled,available}` as booleans. Missing consent defaults to OFF. `available` indicates whether a provider is active in the current profile.
- `PUT /api/sleep/ai/consent` accepts exactly `{"enabled":true}` or `{"enabled":false}` and returns the same shape as GET. It stores one preference per account in `sleep_ai_consent`, keyed by the JWT subject. Revocation is accepted even when the provider is disabled. Enabling while disabled returns `409 SLEEP_STORY_DISABLED`. An unsupported JSON shape or query parameters return `400 INVALID_REQUEST`. Request decoding follows the shared Problem Details conventions.
- `GET /api/sleep/recent/story` returns `{status}` for `DISABLED`, `CONSENT_REQUIRED` or `INELIGIBLE`. A `STORY` or `FALLBACK` result includes `headline` and one or two `observations`. Each observation has `insightCode`, `text`, `dataContext` and `evidenceFingerprint` matching the evidence version of that insight's current Detail. Clients can open that Detail by code and fetch it again. A cooldown `FALLBACK` additionally includes a localized `notice`; other responses omit that field.
- `POST /api/sleep/ai/explanations` accepts `{"insightCode":"B01","evidenceVersion":"..."}` with Bearer authentication. Supported codes are A01–A05 and B01–B04. An optional `timeZone` string is accepted only with A04; without an explicit zone A04 remains ineligible. Existing request bodies remain valid. The optional version is a hint. The server uses current READY evidence and rechecks it and consent before returning. Status `EXPLANATION` or `FALLBACK` includes `observation`, `evidenceBasis`, `limitations` and `evidenceFingerprint`. Other statuses contain only `status` (`DISABLED`, `CONSENT_REQUIRED`, `INELIGIBLE`, `STALE`). Responses use `Cache-Control: no-store`. The fake provider is limited to dev/test. The sandbox Gemini provider shares the Redis cache and successful-generation cooldown rules below.

The service needs current verified Published READY evidence and at least one insight beyond A01. Story reads the full published A/B catalog independently of Recent highlight ranking and its three-card cap. It includes every READY, unlocked supported candidate, sorted by code; an unrelated locked/unverified code does not remove verified candidates. A04 participates only for the explicit request zone. The local fake provider receives immutable normalized sample and evidence maps, per-insight period, policy and Detail evidence version, plus the current reporting period and selected zone. A03/B03 carry both completed trend windows, sample sizes and baseline evidence. No account identifiers, journal rows or source references are sent. The server validates Vietnamese sentences against evidence-derived templates and checks the 120-word budget. After generation, it reads consent and the full published candidate pool again. A versioned SHA-256 identity covers normalized internal evidence, including nested evidence, every candidate, windows and selection. Maps use sorted keys, numbers use canonical decimal values and lists retain semantic order; provider evidence is deeply immutable. The identity is internal and does not replace any public Detail `evidenceVersion`, observation `evidenceFingerprint` or Explanation version hint. Cache keys additionally namespace this identity by authenticated owner and prompt/policy version. A consent revision change suppresses all output. Invalid or stale provider output can yield a deterministic fallback only when the current pool is eligible and consent still matches. If eligibility has been lost, the response has no content. Explanation compares its single current Detail payload in the same way; changed evidence returns `STALE` (or `INELIGIBLE` if no longer READY), without publishing the generated text or generating again. Revoke/re-enable during either generation is detected by consent revision and suppresses all content. See [P6-EVIDENCE delivery](p6-evidence.md) for verification and deferred units.

Story and Explanation use separate Redis namespaces with a non-sliding 24-hour TTL, written only for validated publishable success. Keys hash the authenticated owner, complete evidence identity (including exact periods/baseline and candidate policies), content-policy version and the respective prompt version. Gemini cache keys also include its adapter and model revision. Cache hits retain the existing `STORY`/`EXPLANATION` response shapes and must pass current eligibility, evidence/window, consent revision and content validation checks. Stale/withdrawn entries remain until expiry; reads and consent changes do not delete them or extend their TTL. Redis read/decoding failure returns current deterministic `FALLBACK` without calling the provider. Redis write failure also returns `FALLBACK`. Neither fallback is cached. See [P6-CACHE delivery and verification limits](p6-cache.md).

On a cache miss, Story generation is limited to once per account within 15 minutes of successful publication. The first successful generation on a new reporting day (`Asia/Ho_Chi_Minh`) is exempt from the previous day's remaining cooldown. Explanation generation has a separate five-minute cooldown per account and insight code, across evidence versions and reference-zone changes. Both intervals allow generation at the exact expiry instant. Current valid cache hits bypass cooldown without starting or extending it. Failed, invalid, stale or consent-suppressed output and cache failures do not start cooldown. A blocked request rechecks current evidence/consent and returns deterministic `FALLBACK`, with the Story notice described above. No timer, retry or automatic provider call runs on expiry. Cooldown state is held by the current backend service instance; restarts clear it and separate instances do not share it. See [P6-COOLDOWN delivery](p6-cooldown.md).

P3 is a development/test preview. The fake provider requires the Spring profile `dev` or `test` and `VITALOOM_SLEEP_STORY_ENABLED=true`. The [Gemini adapter](p6-gemini-sandbox.md) uses a separate disabled-by-default sandbox profile and cache identity. Explicit `prod` or `production` profiles disable both providers. Production enablement remains gated. Redis uses `REDIS_HOST`/`REDIS_PORT` (localhost/6379 by default), with 500ms connection and command timeouts. No Redis infrastructure is started by the application.

Story and Explanation coalesce equal in-flight requests within one service instance. Identity includes the authenticated owner, exact normalized evidence/windows (the full Story candidate pool or one Explanation code), reference zone, content policy/prompt versions and consent revision. The shared flight covers cache lookup through validated publication, so concurrent waiters do not start duplicate provider work or cache/cooldown writes. Every caller, including the leader, rechecks its current consent revision and evidence before returning shared content and uses its own total deadline; timing out or interrupting a waiter does not cancel the shared generation. The cache dispatch guard rechecks the original consent revision within the request budget immediately before dispatch; cooldown recording also requires current consent. If final consent validation cannot complete within budget, the response is content-free `CONSENT_REQUIRED`. Completed and failed flights are removed. Separate owners, evidence identities, consent revisions and Story/Explanation operations never share flights. There is no cross-instance coordination. See [P6-SINGLEFLIGHT delivery](p6-singleflight.md).

Story and Explanation use monotonic total budgets of 20 seconds and 10 seconds respectively, including prechecks, cache reads, provider work and publication validation. Consent, evidence and cache reads run in a bounded, cancellable worker pool. Provider work runs in its own bounded, cancellable worker pool; a typed transient provider failure can be retried once within the same budget after current consent revision and evidence identity are rechecked. Permanent failures, invalid output, and changed/withdrawn evidence are not retried. If the deadline expires, a response can use only deterministic fallback derived from consent and evidence revalidated within budget; when freshness cannot be established, content is suppressed. Cache writes are serialized first, then the Redis adapter checks the request's dispatch guard immediately before issuing the command. The Redis command has configured connection and command timeouts; cancellation cannot roll back a command already dispatched. Expired provider output cannot be published or start cooldown state.
