# Heart Rate API Contract

**Status:** Canonical MVP contract  
**Related data design:** [Heart Rate Data Model & Persistence Design](../../architecture/heart-rate-data-design.md)

## Summary

### `GET /api/statistics/heart-rate/summary`

The former `GET /api/heart-rate/summary` path is replaced by this Statistics-owned route and is not retained as a compatibility alias. Heart Rate ingestion remains at `POST /api/heart-rate/batch`.

Read-only Heart Rate screen summary for an authenticated account. Returns **Latest Recorded Heart Rate** from the account's entire accepted history plus **Daily Average Heart Rate** for the selected calendar date. The Backend selects and calculates both; the mobile client must not calculate authoritative values.

**Authentication:** Bearer access JWT is required. Derive `accountId` from the authenticated principal, never from the request. Do not expose account-scoped health reads against unisolated/shared metric records; ingest and read paths must both enforce account ownership before handling real multi-user data.

**Query parameters:**

| Parameter | Type | Required | Behavior |
| --- | --- | --- | --- |
| `date` | `YYYY-MM-DD` | No | Statistics day in `Asia/Ho_Chi_Minh`; defaults to today's local date. Accept only today or past dates. Invalid or future dates return `400 INVALID_REPORT_DATE`. |

Successful response — `200 OK` (example assumes local today is `2026-11-06`):

```json
{
  "date": "2026-11-06",
  "timezone": "Asia/Ho_Chi_Minh",
  "asOf": "2026-11-06T07:31:00.000Z",
  "latestRecorded": {
    "heartRateBpm": 82,
    "occurredAt": "2026-11-06T07:30:15.000Z"
  },
  "dailyAverage": {
    "heartRateBpm": 76.5,
    "dataStatus": "CURRENT_DAY_PARTIAL"
  }
}
```

| Response field | Type | Meaning |
| --- | --- | --- |
| `date` | local calendar date | Selected date used **only** by Daily Average. |
| `timezone` | string | Always `Asia/Ho_Chi_Minh` for the MVP. |
| `asOf` | UTC ISO 8601 instant | Server snapshot/read time; **not** the measurement time. |
| `latestRecorded` | object or `null` | Latest accepted measurement by `occurredAt` across **all dates** for this account; `null` if no eligible measurement exists. |
| `latestRecorded.heartRateBpm` | integer | BPM of one accepted measurement, not an average. |
| `latestRecorded.occurredAt` | UTC ISO 8601 instant | Time the measurement occurred; show as “Đo lúc ...”, never substitute `receivedAt` or `asOf`. |
| `dailyAverage` | object | Always present for successful requests. |
| `dailyAverage.heartRateBpm` | number or `null` | Arithmetic mean of accepted BPM values for `date`, or `null` when no accepted measurement is recorded for that date. |
| `dailyAverage.dataStatus` | enum | Daily-result temporal/completeness state; independent of whether BPM is null. |

**Allowed `dailyAverage.dataStatus` values:**

| Value | When to return | UI meaning |
| --- | --- | --- |
| `CURRENT_DAY_PARTIAL` | Selected date is today | Provisional: reflects only accepted samples available when read. |
| `HISTORY_UNVERIFIED` | Selected date is past and data completeness has not been verified | Historical result may be incomplete; default for historical dates without a completeness mechanism. |
| `HISTORY_VERIFIED` | Historical-day completeness is positively confirmed under an approved metric-owner policy | Use only once a verification mechanism exists; never infer verification from date alone. |

**Business / read semantics:**

- Latest is selected by the **maximum measurement time `occurredAt`** within the authenticated account, not by `receivedAt` or latest ingestion batch. Out-of-order batches must not displace a later-measured sample. Records with identical `occurredAt` are allowed; Technical Design must provide a stable deterministic tie-break before implementation.
- Daily Average is `sum(heartRateBpm) / count(accepted measurements)` for the half-open interval `[startOfDay, startOfNextDay)` determined in `Asia/Ho_Chi_Minh`. It is an unweighted arithmetic mean; no min/max, zones, trend or medical inference.
- The `date` query filters **only Daily Average**. Switching from today to a past day does **not** change `latestRecorded`, unless the actual underlying accepted account records have changed.
- A missing latest value and a missing daily average are independent. Never use `0` or one metric as a substitute for missing data in the other. Null means no accepted record currently available; it does not certify completeness of a historical day.
- Latest must be labelled *Latest Recorded* rather than *Current*, *Live* or *Realtime*. Always show the actual `occurredAt` of the observation, even if that observation is old. No realtime stream, refresh interval, device-connection claim or freshness threshold is defined.
- Both metrics are read-only and sourced from accepted `heart_rate_measurements` documents; no new persistent aggregate/collection is introduced. Account isolation and authentication are mandatory.
- `GET /api/statistics/daily` remains unchanged: it only returns today's `dailyAverageHeartRateBpm` for the dashboard, not Latest Recorded HR.

Example — user has no accepted Heart Rate measurements (past date requested):

```json
{
  "date": "2026-11-05",
  "timezone": "Asia/Ho_Chi_Minh",
  "asOf": "2026-11-06T07:31:00.000Z",
  "latestRecorded": null,
  "dailyAverage": {
    "heartRateBpm": null,
    "dataStatus": "HISTORY_UNVERIFIED"
  }
}
```

**Errors:** `400 INVALID_REPORT_DATE` for malformed/invalid/future `date`, `401 UNAUTHORIZED` for missing/invalid access credentials, and a standard `5xx` error for retrieval failure. Use the section 8 error envelope. A failed read must not return `200` with null BPM as though there were simply no measurements.

**Implementation decisions:** equal-`occurredAt` latest records use ascending lexical `deviceId`, then `eventId`. Heart Rate ingestion rejects an event whose `occurredAt` is strictly later than the receipt instant captured for its batch with item-level `FUTURE_OCCURRED_AT`; there is no clock-skew grace period. Historical completeness and late/corrected-data policy remain open; do not emit `HISTORY_VERIFIED` without a positive verification mechanism.

## Aggregated Time Series

The Daily, Weekly and Custom routes below require a Bearer access JWT. The authenticated JWT subject is the only account scope; no account identifier is accepted as a query parameter. Every timestamp is an absolute UTC instant serialized with an explicit UTC representation. All calendar boundaries use `Asia/Ho_Chi_Minh`.

| Route | Query parameters | Range and buckets |
| --- | --- | --- |
| `GET /api/statistics/heart-rate/daily` | Optional `date=YYYY-MM-DD`; defaults to today's local date. | One local date, 96 buckets at 15 minutes each. Future dates are invalid. |
| `GET /api/statistics/heart-rate/weekly` | Optional `weekStartDate=YYYY-MM-DD`; defaults to the current local Monday. | Monday 00:00 through the next Monday 00:00, end exclusive; 168 hourly buckets. The current week is allowed and future positions remain empty. A supplied date must be Monday. |
| `GET /api/statistics/heart-rate/custom` | Required `startDate=YYYY-MM-DD` and `endDate=YYYY-MM-DD`. | Inclusive local dates, ordered, ending no later than today; 1–30 calendar days, one hourly bucket per hour (24–720 buckets). |

Dates must be strict, valid `YYYY-MM-DD` calendar dates. A requested local day/week/range whose zone-derived duration differs from its nominal 24-hour days (for example, a historical timezone transition) is rejected with the route's corresponding invalid-date code, preserving the fixed bucket counts.

All routes return the same shape. `endAt` is exclusive; bucket `startAt` values are chronological UTC instants. Each sample is assigned by `occurredAt` to a half-open bucket `[startAt, next bucket start)`. `minBpm`, `avgBpm` and `maxBpm` are calculated from raw accepted observations; average is the unweighted arithmetic mean. Empty buckets remain present with `null` min/average/max and `sampleCount: 0`.

```json
{
  "startAt": "2026-11-09T17:00:00Z",
  "endAt": "2026-11-10T17:00:00Z",
  "timezone": "Asia/Ho_Chi_Minh",
  "intervalMinutes": 15,
  "buckets": [
    {"startAt": "2026-11-09T17:00:00Z", "minBpm": 65, "avgBpm": 72.5, "maxBpm": 84, "sampleCount": 30},
    {"startAt": "2026-11-09T17:15:00Z", "minBpm": null, "avgBpm": null, "maxBpm": null, "sampleCount": 0}
  ]
}
```

Invalid or impossible dates, future Daily dates, invalid/future weeks, missing or invalid Custom parameters, reversed ranges, ranges over 30 days and historical transition ranges return `400` with `INVALID_REPORT_DATE`, `INVALID_WEEK_START_DATE` or `INVALID_DATE_RANGE` as appropriate, using the section 8 error envelope. Missing or invalid Bearer credentials return `401 UNAUTHORIZED`.

## Batch ingestion

### `POST /api/heart-rate/batch`

Accepts raw Heart Rate measurements from one physical or simulated device per batch. Batch interval is controlled by the Producer (currently targeted at approximately 3–5 seconds); it is not an API timing constraint.

Request:

```json
{
  "deviceId": "device-001",
  "runId": "run-20261105-001",
  "events": [
    {
      "eventId": "hr-run-001-000001",
      "occurredAt": "2026-11-05T12:30:15.000Z",
      "heartRateBpm": 82
    },
    {
      "eventId": "hr-run-001-000002",
      "occurredAt": "2026-11-05T12:30:16.000Z",
      "heartRateBpm": 84
    }
  ]
}
```

#### Request fields

| Field | Type | Required | Rule |
|---|---|---:|---|
| `deviceId` | string | Yes | Stable identifier for the device; reuse it across Producer runs. One batch contains measurements from one device only. |
| `runId` | string | Yes | Identifies the Producer run for diagnostics; it is not part of measurement identity. |
| `events` | array | Yes | Contains 1–500 measurements per request. |
| `events[].eventId` | string | Yes | Stable unique measurement ID within the account/device. Reuse it for every retry of that measurement. |
| `events[].occurredAt` | ISO 8601 timestamp | Yes | Device measurement time. Producers should send UTC with `Z`; Backend parses it as an `Instant`. |
| `events[].heartRateBpm` | integer | Yes | Must be between 1 and 300 inclusive. This is an input sanity boundary, not a clinical normal range. |

Additional rules:

- `accountId` is not accepted in the request. Backend resolves it from the authenticated JWT subject for both ingestion and reads.
- `deviceId` is a source identifier, not an authentication credential. The MVP has no Device registry or device-registration workflow.
- `eventId` is the primary idempotency key for an individual measurement. The Producer generates it once and preserves it across retries.
- `occurredAt` is authoritative for history and statistics-day assignment. Out-of-order delivery is allowed.
- An `occurredAt` later than the batch receipt instant is rejected item by item with `FUTURE_OCCURRED_AT`; no clock-skew tolerance is applied. Accepted values are persisted at BSON Date millisecond precision.
- Multiple measurements may have the same `occurredAt`, including measurements from different devices. Timestamp is not a unique key.
- Backend creates `receivedAt`; clients cannot set or override it.
- Persist accepted items to the separate `heart_rate_measurements` collection. Do not store Heart Rate and Steps in a generic shared event collection.
- The measurement document, unique index and history index are defined in [Heart Rate Data Model & Persistence Design](../../architecture/heart-rate-data-design.md).

#### Partial-acceptance response

A structurally valid batch is processed item by item. One invalid or duplicate measurement does not cause valid measurements in the same batch to be discarded.

Successful processing — `200 OK`:

```json
{
  "runId": "run-20261105-001",
  "received": 3,
  "accepted": 1,
  "duplicates": 1,
  "rejected": 1,
  "results": [
    {
      "index": 0,
      "eventId": "hr-run-001-000001",
      "status": "ACCEPTED"
    },
    {
      "index": 1,
      "eventId": "hr-run-001-000002",
      "status": "DUPLICATE"
    },
    {
      "index": 2,
      "eventId": "hr-run-001-000003",
      "status": "REJECTED",
      "error": {
        "code": "INVALID_HEART_RATE",
        "message": "heartRateBpm must be an integer between 1 and 300."
      }
    }
  ]
}
```

Response rules:

- `received` is the number of input items.
- `accepted` counts newly persisted measurements.
- `duplicates` counts already accepted measurements resent with the same account, device, event ID and measurement payload.
- `rejected` counts items not persisted because of invalid measurement data or conflicting reuse of an event ID.
- The invariant is `received = accepted + duplicates + rejected`.
- `results` preserves request order. `index` is zero-based and lets the Producer correlate a rejected item even when its `eventId` is missing or invalid.
- Each result has one status: `ACCEPTED`, `DUPLICATE` or `REJECTED`.
- A repeated identity with the same normalized `occurredAt` and `heartRateBpm` is `DUPLICATE`. Reusing the same account/device/event ID with different measurement content is `REJECTED` with code `EVENT_ID_CONFLICT`.
- Validation errors are item-level results. Malformed JSON or an invalid batch envelope (missing `deviceId`, missing `runId`, or an empty/oversized `events` array) returns `400 Bad Request` for the whole request.
- A transient infrastructure/database failure returns a server error. The batch may have partially persisted before the failure; retry the same measurement IDs. The unique index makes already persisted items safe to resend.
- The endpoint processes synchronously and returns item outcomes; it does not use `202 Accepted`.

A separate HTTP `Idempotency-Key` header and batch-response replay store are deferred. Stable measurement `eventId` values and the MongoDB unique index are sufficient for the MVP's retry-safe persistence.

## MVP acceptance checks

- [ ] Heart Rate endpoint accepts one device per batch and stores each accepted measurement in `heart_rate_measurements`.
- [ ] Heart Rate Daily Average is the unweighted mean of accepted measurements by `occurredAt`, not the latest observation.
- [ ] `GET /api/statistics/heart-rate/summary` returns account-scoped Latest Recorded HR across all days and Daily Average for the chosen day; requires Bearer authentication and rejects future/invalid dates.
- [ ] Changing the day leaves Latest unchanged; out-of-order measurements do not replace newer `occurredAt` values; same-time ties have a deterministic resolution.
- [ ] Daily status distinguishes current-day partial from historical unverified, and claims historical verification only when explicitly supported.
- [ ] No-data returns null without 0 placeholders, and failed retrieval produces an error instead of an empty successful response.
- [ ] Heart Rate batch responses expose per-item accepted, duplicate and rejected outcomes.
- [ ] Heart Rate retries reuse stable `eventId` values; duplicate and concurrent writes do not create a second measurement.
- [ ] MongoDB unique indexes prevent concurrent duplicate inserts.
- [ ] Daily boundaries use `Asia/Ho_Chi_Minh`, starting at 00:00.
- [ ] Raw event timestamps are preserved independently of server receipt time.
