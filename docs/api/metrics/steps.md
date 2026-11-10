# Steps API Contract

**Status:** Existing batch contract plus proposed Statistics contract  
**Timezone:** `Asia/Ho_Chi_Minh`  
**Related data design:** [Steps Data Model & Persistence Design](../../architecture/steps-data-design.md)

## Batch ingestion

### `POST /api/steps/batch`

Accepts raw step-increment events from the Go producer. The producer sends increments, not a precomputed cumulative total.

Request:

```json
{
  "runId": "demo-run-001",
  "events": [
    {
      "eventId": "demo-run-001-0001",
      "occurredAt": "2026-10-24T02:00:00Z",
      "stepsDelta": 12
    },
    {
      "eventId": "demo-run-001-0002",
      "occurredAt": "2026-10-24T02:00:10Z",
      "stepsDelta": 8
    }
  ]
}
```

Rules:

- `runId` identifies a producer run and is useful for producer-run diagnostics.
- `eventId` is generated when the producer creates an event. It must remain unchanged when that event is retried.
- `occurredAt` is when the simulated steps occurred.
- `stepsDelta` is a non-negative integer increment, not a cumulative snapshot.
- Store accepted raw events. Daily steps are calculated by summing unique accepted increments whose `occurredAt` falls within the local day.
- Duplicate `eventId` values must not increase the total. MongoDB uniqueness is the final guard against concurrent duplicate inserts.

Successful response — `202 Accepted`:

```json
{
  "runId": "demo-run-001",
  "received": 2,
  "accepted": 2,
  "duplicates": 0
}
```

Counts describe this submission. Retrying a previously accepted batch may return `accepted: 0` and a positive `duplicates` count.

### Confirmed event-time semantics

For the Steps MVP, `occurredAt` is the point in time when an increment is completed and recorded. The entire `stepsDelta` value is assigned to the time bucket containing that timestamp.

This is a deliberate simplification. The API does not claim to know the exact distribution of individual steps inside an increment interval. Do not split one increment across buckets or calendar days.

## Steps Statistics APIs — Draft for review

**Status:** Proposed contract; not approved for implementation yet.  
**Purpose:** Define backend capabilities independently of current Mobile UI consumption.

The three query use cases should have distinct endpoints and distinct public Statistics service methods. They may reuse the same Steps-specific aggregation implementation where appropriate, but the service must not infer the mode from a generic `period` parameter.

### Proposed routes

| Endpoint | Parameters | Proposed range | Proposed output buckets |
|---|---|---|---|
| `GET /api/statistics/steps/daily` | Optional `date=YYYY-MM-DD`; defaults to today's local date | One local calendar day | 24 hourly buckets |
| `GET /api/statistics/steps/weekly` | Optional `weekStartDate=YYYY-MM-DD`; defaults to current local Monday | Monday 00:00 through next Monday 00:00, end exclusive | 7 daily buckets |
| `GET /api/statistics/steps/custom` | Required `startDate=YYYY-MM-DD`, `endDate=YYYY-MM-DD` | Inclusive local dates, proposed limit 1–30 days | One daily bucket per date |

These bucket resolutions and the 30-day Custom limit are proposals for review, not settled requirements. All calendar boundaries use `Asia/Ho_Chi_Minh`.

### Proposed response shape

The three endpoints should share a Steps-specific response DTO. Do not reuse the Heart Rate response DTO because the aggregation semantics differ.

```json
{
  "startAt": "2026-11-10T00:00:00+07:00",
  "endAt": "2026-11-11T00:00:00+07:00",
  "timezone": "Asia/Ho_Chi_Minh",
  "bucketSizeMinutes": 60,
  "totalSteps": 8240,
  "buckets": [
    {
      "startAt": "2026-11-10T08:00:00+07:00",
      "endAt": "2026-11-10T09:00:00+07:00",
      "steps": 420,
      "eventCount": 30
    }
  ]
}
```

Proposed field semantics:

- `startAt` is the inclusive start of the requested range; `endAt` is exclusive.
- Every bucket has inclusive `startAt` and exclusive `endAt`.
- `steps` is the sum of `stepsDelta` for events assigned to that bucket.
- `eventCount` counts raw events assigned to the bucket.
- If a bucket has no events, return `steps: null` and `eventCount: 0`.
- If one or more events exist but their increments sum to zero, return `steps: 0`.
- `totalSteps` is `null` if the overall range has no events; otherwise it is the sum of all accepted increments in the requested range.
- Buckets are ordered chronologically and empty buckets remain present.

### Proposed validation and security

- Daily dates must be valid and not in the future.
- Weekly start date must be Monday; the current week is allowed, future weeks are rejected.
- Custom requires both dates, an ordered inclusive range, an end date no later than today, and the approved maximum range.
- The new Steps statistics routes should require Bearer JWT authentication and derive `accountId` from the authenticated subject.
- The current Steps ingestion route's authentication scope must be reviewed together with its implementation before real multi-user use.

### Open decisions before this draft becomes canonical

1. Confirm Daily hourly buckets, Weekly daily buckets and Custom daily buckets.
2. Confirm the Custom maximum range.
3. Confirm whether empty buckets return `null` as proposed, while observed zero remains `0`.
4. Confirm exact timestamp serialization and whether `bucketSizeMinutes` is preferred over a unit enum.
5. Confirm route-level authentication for Steps ingestion and Statistics.
6. Confirm the batch response behavior: retain the existing aggregate-count `202 Accepted` response or align with Heart Rate's per-item partial-acceptance response.
7. Confirm whether `deviceId` is added to the Steps batch envelope and raw record.

No Mobile UI behavior is implied by this draft. The Mobile team can later choose which of these documented APIs to consume.


## Current baseline acceptance check

- [ ] Steps endpoint accepts raw increments and calculates daily totals without counting duplicate events twice.
