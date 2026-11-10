# Steps API Contract

**Status:** Canonical MVP ingestion contract; Statistics read contract is pending the remaining decisions listed below  
**Timezone:** `Asia/Ho_Chi_Minh`  
**Related data design:** [Steps Data Model & Persistence Design](../../architecture/steps-data-design.md)

## Batch ingestion

### `POST /api/steps/batch`

Accepts raw step-increment events from the VitaLoom Data Producer. The Producer sends increments, not a precomputed cumulative total.

**Authentication:** Bearer access JWT is required. Backend derives `accountId` from the authenticated JWT subject; the request must not contain an account ID.

Request:

```json
{
  "deviceId": "steps-device-001",
  "runId": "demo-run-001",
  "events": [
    {
      "eventId": "demo-run-001-0001",
      "occurredAt": "2026-11-10T01:00:01.000Z",
      "stepsDelta": 3
    },
    {
      "eventId": "demo-run-001-0002",
      "occurredAt": "2026-11-10T01:00:02.000Z",
      "stepsDelta": 2
    }
  ]
}
```

### Request and event rules

| Field | Type | Required | Rule |
|---|---|---:|---|
| `deviceId` | string | Yes | Stable device/source identifier; non-blank and at most 128 characters. One batch contains events from one device. |
| `runId` | string | Yes | Producer-run diagnostic ID; non-blank and at most 128 characters. It is not part of event identity. |
| `events` | array | Yes | Contains 1–500 events per request. |
| `events[].eventId` | string | Yes | Stable event ID; non-blank and at most 128 characters. Reuse it for every retry of that event. |
| `events[].occurredAt` | ISO 8601 instant | Yes | Time when the increment is completed and recorded. Producer should send UTC with `Z`. |
| `events[].stepsDelta` | integer | Yes | Must be from **1 through 5 inclusive** for the MVP Producer's one-second measurement interval. |

Additional rules:

- `stepsDelta` is an increment, not a cumulative counter snapshot. The entire increment is assigned to the bucket and local date containing `occurredAt`.
- A valid Steps event must have `stepsDelta > 0`. Zero and negative values are rejected with item-level `INVALID_STEPS_DELTA`; do not persist zero-value events.
- When the Producer observes no step increment, it must not create an event for that observation. If a flush has no events, skip the Steps HTTP request; an empty `events` array is an invalid batch envelope.
- The inclusive upper bound of 5 is an MVP input-sanity boundary calibrated to the current Producer's one-second measurement interval. It is not a universal physiological limit. Changing the measurement interval or introducing a real wearable/source requires revisiting this boundary and the event semantics before integration.
- `occurredAt` is a point-in-time timestamp for the completed increment. It does not describe an interval start/end and does not prove continuous observation or data coverage. Do not split an increment across buckets or midnight.
- Backend captures one batch receipt instant as `receivedAt`. Any event whose `occurredAt` is strictly later than that instant is rejected with `FUTURE_OCCURRED_AT`; no clock-skew grace period is applied.
- Out-of-order delivery is allowed. Multiple events may have the same `occurredAt`; timestamp is not a unique key.
- Normalize timestamps to MongoDB BSON Date millisecond precision before persistence and before comparing duplicate payloads.
- `deviceId` identifies the source stream but is not an authentication credential. It is copied into each accepted raw measurement.
- Cross-device overlap detection/reconciliation is out of MVP scope. The current Producer scenario should send one canonical Steps stream per account. Backend does not infer that events from different device IDs are duplicates; Statistics sums all accepted events in the requested account/time range.
- Backend creates `receivedAt`; clients cannot set or override it.
- Persist accepted events in the metric-specific `step_measurements` collection. Do not use a generic shared event collection.

A malformed JSON body or invalid batch envelope (missing/invalid `deviceId` or `runId`, or an empty/oversized `events` array) returns `400 Bad Request` for the whole request. Invalid event content is handled item by item.

### Partial-acceptance response

A structurally valid batch is processed item by item. One invalid or duplicate event does not cause valid events in the same batch to be discarded.

Successful processing — `200 OK`:

```json
{
  "runId": "demo-run-001",
  "received": 3,
  "accepted": 1,
  "duplicates": 1,
  "rejected": 1,
  "results": [
    {
      "index": 0,
      "eventId": "demo-run-001-0001",
      "status": "ACCEPTED"
    },
    {
      "index": 1,
      "eventId": "demo-run-001-0002",
      "status": "DUPLICATE"
    },
    {
      "index": 2,
      "eventId": "demo-run-001-0003",
      "status": "REJECTED",
      "error": {
        "code": "INVALID_STEPS_DELTA",
        "message": "stepsDelta must be an integer between 1 and 5 for the MVP Producer contract."
      }
    }
  ]
}
```

Response rules:

- `received` is the number of input items.
- `accepted` counts newly persisted events.
- `duplicates` counts events already accepted with the same account, device, event ID and immutable measurement payload.
- `rejected` counts invalid events or conflicting reuse of an event ID.
- The invariant is `received = accepted + duplicates + rejected`.
- `results` preserves request order. `index` is zero-based and allows the Producer to correlate a rejected item.
- Each result has exactly one status: `ACCEPTED`, `DUPLICATE` or `REJECTED`.
- A repeated identity with the same normalized `occurredAt` and `stepsDelta` is `DUPLICATE`. Reusing the same identity with different measurement content is `REJECTED` with `EVENT_ID_CONFLICT`.
- Item-level validation codes include `INVALID_EVENT_ID`, `INVALID_OCCURRED_AT`, `FUTURE_OCCURRED_AT`, `INVALID_STEPS_DELTA` and `EVENT_ID_CONFLICT`.
- A transient infrastructure/database failure returns a server error. The batch may have partially persisted before the failure; retry the same event IDs. MongoDB's unique index makes already persisted events safe to resend.
- The endpoint processes synchronously and returns item outcomes; it does not use `202 Accepted`.
- A separate HTTP `Idempotency-Key` header and batch-response replay store are deferred. Stable event IDs and the MongoDB unique index provide retry-safe persistence for the MVP.

## Steps Statistics APIs — proposed read contract

**Status:** Proposed; do not implement until the remaining range/resolution decisions below are confirmed.  
**Purpose:** Define backend capabilities independently of current Mobile UI consumption.

The three query use cases have separate endpoints and separate public Statistics service methods. They may reuse a Steps-specific aggregation implementation where appropriate, but the service must not infer the mode from a generic `period` parameter.

### Proposed routes

| Endpoint | Parameters | Proposed range | Proposed output buckets |
|---|---|---|---|
| `GET /api/statistics/steps/daily` | Optional `date=YYYY-MM-DD`; defaults to today's local date | One local calendar day | 24 hourly buckets |
| `GET /api/statistics/steps/weekly` | Optional `weekStartDate=YYYY-MM-DD`; defaults to current local Monday | Monday 00:00 through next Monday 00:00, end exclusive | 7 daily buckets |
| `GET /api/statistics/steps/custom` | Required `startDate=YYYY-MM-DD`, `endDate=YYYY-MM-DD` | Inclusive local dates, proposed limit 1–30 days | One daily bucket per date |

All calendar boundaries use `Asia/Ho_Chi_Minh`. The route separation is the intended API shape; bucket resolutions and the 30-day Custom limit remain proposals until explicitly confirmed.

### Proposed response shape

The three endpoints should share one Steps-specific response DTO. Do not reuse the Heart Rate response DTO because the aggregation semantics differ.

```json
{
  "startAt": "2026-11-10T00:00:00Z",
  "endAt": "2026-11-11T00:00:00Z",
  "timezone": "Asia/Ho_Chi_Minh",
  "bucketSizeMinutes": 60,
  "totalSteps": 5240,
  "buckets": [
    {
      "startAt": "2026-11-10T01:00:00Z",
      "endAt": "2026-11-10T02:00:00Z",
      "steps": 420,
      "eventCount": 30
    },
    {
      "startAt": "2026-11-10T02:00:00Z",
      "endAt": "2026-11-10T03:00:00Z",
      "steps": null,
      "eventCount": 0
    }
  ]
}
```

Proposed field semantics:

- Top-level `startAt` is inclusive and `endAt` is exclusive. All serialized timestamps are absolute UTC instants.
- Each bucket has inclusive `startAt` and exclusive `endAt`; buckets are chronological and contiguous.
- `steps` is the sum of accepted `stepsDelta` values assigned to the bucket.
- `eventCount` counts accepted raw events assigned to the bucket.
- If a bucket has no accepted events, return `steps: null` and `eventCount: 0`. Do not synthesize or persist a zero-valued raw event.
- Because ingestion accepts only positive `stepsDelta`, a bucket with one or more accepted events has a positive `steps` total; a valid event-bearing bucket does not return `steps: 0`.
- `totalSteps` is `null` if the entire requested range contains no accepted events; otherwise it is the sum of accepted increments in the requested range.
- Empty buckets remain present in the response so the client receives a stable time axis. Empty data does not establish that the user did not walk or that the Producer was continuously online.
- The proposed `bucketSizeMinutes` is 60 for Daily and 1440 for Weekly/Custom. Confirm whether this numeric field is preferred over a bucket-unit enum before implementation.

### Proposed query validation and security

- Daily date must be a valid local calendar date and cannot be in the future.
- Weekly start date must be Monday; the current week is allowed and future weeks are rejected. Future positions within the current week remain empty until their event times occur.
- Custom requires both dates, an ordered inclusive range and an end date no later than today. Its proposed maximum is 30 calendar days.
- All Steps Statistics routes require a Bearer access JWT and derive `accountId` from the authenticated subject. No account identifier is accepted in query parameters.
- The endpoint contract is defined here, but the Steps routes are not implemented in the Backend yet.

### Remaining decisions before implementation

1. Confirm the proposed bucket resolutions: 24 hourly buckets for Daily and 7 daily buckets for Weekly.
2. Confirm the proposed Custom range limit of 1–30 calendar days.
3. Confirm `bucketSizeMinutes` versus a bucket-unit enum. Proposed values are 60 for Daily and 1440 for Weekly/Custom.

No Mobile UI behavior is implied by this contract. The Mobile team can later choose which documented APIs to consume.

## MVP acceptance checks

- [ ] Steps batch ingestion requires Bearer authentication and derives account ownership from the JWT subject.
- [ ] The batch envelope requires one stable `deviceId`, one `runId` and 1–500 events.
- [ ] Every accepted event has a stable event ID, a valid non-future `occurredAt` and integer `stepsDelta` from 1 through 5.
- [ ] Zero/negative/over-limit increments are rejected and zero-value events are never persisted.
- [ ] Identical retries are duplicates; conflicting reuse of an event ID is rejected; concurrent duplicates are prevented by a MongoDB unique index.
- [ ] Out-of-order events are accepted and the full increment is assigned to the bucket containing `occurredAt`.
- [ ] Batch responses provide per-item accepted, duplicate and rejected outcomes with consistent counters.
- [ ] Statistics returns null for empty buckets and does not claim that no recorded event proves zero activity or complete data coverage.
