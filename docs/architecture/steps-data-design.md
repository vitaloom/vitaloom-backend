# Steps Data Model & Persistence Design

**Status:** Approved MVP data-design baseline; Statistics range details remain proposed in the API contract  
**Scope:** Raw step-event semantics, identity, MongoDB persistence and the query boundary to Statistics  
**Related API contract:** [Steps API Contract](../api/metrics/steps.md)  
**Architecture ownership:** [ADR-001 — Statistics Owns Aggregate Read Models](./decisions/ADR-001-statistics-owns-aggregate-read-models.md)

This design is specific to Steps. Do not generalize it into a shared Heart Rate / Steps event model or collection.

## 1. Confirmed event semantics and validation

- A Steps event carries an increment (`stepsDelta`), not a cumulative counter snapshot.
- For the MVP Producer's one-second measurement interval, accepted `stepsDelta` is an integer from **1 through 5 inclusive**.
- Zero and negative increments are invalid. Reject them item by item with `INVALID_STEPS_DELTA`; do not persist zero-value events. When no increment is observed, the Producer creates no event.
- The upper bound of 5 is an input-sanity boundary calibrated to the current one-second simulator. It is not a universal physiological limit. A change to the measurement interval or introduction of a real wearable/source requires revisiting the boundary and event semantics before integration.
- `occurredAt` is a point-in-time timestamp representing when an increment is completed and recorded.
- The entire `stepsDelta` is assigned to the time bucket and local calendar date containing `occurredAt`.
- The model does not claim to know the per-step distribution inside an increment interval. An increment that crosses midnight is assigned wholly to the bucket containing its `occurredAt`.
- Backend creates `receivedAt` using one receipt instant captured for the batch; the Producer cannot supply it.
- An event whose `occurredAt` is strictly later than the batch receipt instant is rejected with `FUTURE_OCCURRED_AT`; no clock-skew grace period is applied.
- Out-of-order delivery is accepted. Multiple events may share an `occurredAt`; timestamps are not unique identities.
- Normalize `occurredAt` to BSON Date millisecond precision before persistence and duplicate-payload comparison.
- Daily calendar boundaries use `Asia/Ho_Chi_Minh`.
- Accepted raw events remain the source of truth. Statistics calculates derived totals on read for the current MVP.

This point-in-time model does not provide data-coverage information. An empty time bucket means no accepted event was recorded in that bucket; it does not prove zero steps or continuous Producer observation.

## 2. Batch contract and raw document

The canonical ingestion endpoint is `POST /api/steps/batch`. It requires a Bearer access JWT. Backend derives `accountId` from the authenticated principal; account identity is never accepted from the payload.

The batch envelope requires:

| Field | Type | Rule |
|---|---|---|
| `deviceId` | String | Required, non-blank, maximum 128 characters; stable for one device/source across Producer runs |
| `runId` | String | Required, non-blank, maximum 128 characters; diagnostic metadata only |
| `events` | Array | 1–500 events per batch |

Each event requires:

| Field | BSON / Java type | Rule |
|---|---|---|
| `eventId` | String | Required, non-blank, maximum 128 characters; stable across retries |
| `stepsDelta` | Int32 / Java `int` | Integer from 1 through 5 inclusive under the MVP one-second Producer contract |
| `occurredAt` | BSON Date / Java `Instant` | Valid absolute instant; persisted at millisecond precision |

Collection: `step_measurements`.

Example:

```json
{
  "_id": "ObjectId(...)",
  "accountId": "authenticated-account-id",
  "deviceId": "steps-device-001",
  "eventId": "steps-run-001-000001",
  "stepsDelta": 3,
  "occurredAt": "2026-11-10T01:00:01.000Z",
  "receivedAt": "2026-11-10T01:00:02.000Z"
}
```

| Field | Responsibility |
|---|---|
| `_id` | MongoDB-generated internal document identity |
| `accountId` | String account identifier matching the authenticated User ID / JWT subject |
| `deviceId` | Stable source identity copied from the batch envelope |
| `eventId` | Stable identity of one increment event, reused on retry |
| `stepsDelta` | Positive integer increment from 1 through 5 |
| `occurredAt` | Source event time; increment completion/recording instant |
| `receivedAt` | Backend batch receipt time; not supplied by Producer |

`runId` remains batch diagnostic metadata. It is not copied into the measurement document and is not part of event identity. A separate Device collection, device-registration workflow or device-ownership verification is not introduced for the MVP.

Do not store zero-step marker documents, precomputed daily totals, averages, minimums or maximums in this raw collection.

## 3. Identity, idempotency and indexes

The stable identity of one raw Steps event is:

```text
accountId + deviceId + eventId
```

MongoDB uniqueness is the final concurrency guard. An application-only pre-check is insufficient.

### 3.1. Unique event identity

```javascript
db.step_measurements.createIndex(
  { accountId: 1, deviceId: 1, eventId: 1 },
  { unique: true, name: "uk_steps_account_device_event" }
)
```

For an existing identity:

- Same normalized `occurredAt` and same `stepsDelta`: classify as `DUPLICATE`.
- Different `occurredAt` or `stepsDelta`: classify as `REJECTED` with `EVENT_ID_CONFLICT`.
- `receivedAt` and `runId` do not participate in immutable event payload identity.
- The same `eventId` under a different `deviceId` is a different source identity; there is no cross-device deduplication.

### 3.2. Account time-range query

```javascript
db.step_measurements.createIndex(
  { accountId: 1, occurredAt: 1 },
  { name: "ix_steps_account_occurred_at" }
)
```

This supports account-scoped raw-event queries for Statistics. Query ranges use a half-open interval `[startInclusive, endExclusive)` derived from local calendar boundaries in `Asia/Ho_Chi_Minh`.

Do not add speculative indexes on `receivedAt`, `deviceId` alone or extra sort combinations without a demonstrated query.

## 4. Batch processing behavior

- Process each event independently after validating the overall envelope.
- A valid event is inserted once. A duplicate identity with the same immutable payload returns `DUPLICATE`; conflicting payload reuse returns `REJECTED / EVENT_ID_CONFLICT`.
- Other item-level validation failures return `REJECTED` with a metric-specific error code, including `INVALID_EVENT_ID`, `INVALID_OCCURRED_AT`, `FUTURE_OCCURRED_AT` and `INVALID_STEPS_DELTA`.
- A structurally invalid envelope (missing/invalid `deviceId` or `runId`, or 0 or more than 500 events) returns request-level `400 Bad Request`.
- A successfully processed batch returns `200 OK` with `received`, `accepted`, `duplicates`, `rejected` and ordered per-item results. The invariant is `received = accepted + duplicates + rejected`.
- A transient database/server failure may occur after some events have persisted. The Producer retries the same event IDs; the unique index makes already persisted items safe to resend.
- No HTTP-level idempotency-key store or batch-response replay store is introduced for the MVP.

The behavior intentionally follows the established Heart Rate ingestion pattern while retaining Steps-specific validation and persistence. Do not reuse Heart Rate DTOs, documents, repositories or collections.

## 5. Device attribution and cross-device overlap

`deviceId` is required to identify where an increment stream originated. It is attribution and event-identity metadata, not proof of device ownership and not a cross-device deduplication algorithm.

Cross-device duplicate detection/reconciliation is **out of MVP scope**:

- Different device IDs and event IDs are different source events.
- Similar timestamps or overlapping time windows do not prove that two increments represent the same steps.
- Backend must not automatically discard or merge increments merely because timestamps overlap.
- Statistics sums accepted events in the authenticated account/time range. If multiple independent devices submit overlapping activity, their increments can be double-counted.
- The current Producer scenario must therefore use one canonical Steps stream per account. Supporting simultaneous Phone/Watch streams requires a separately approved source-priority or reconciliation policy.

## 6. Statistics query boundary and aggregation ownership

The Steps feature owns raw increment ingestion, validation, event identity, persistence and raw-data query capabilities. Statistics owns all derived Steps totals and time-bucket calculations.

The Steps service exposes account-scoped raw events within a supplied half-open instant range `[startInclusive, endExclusive)`. Keep MongoDB query construction and persistence models inside Steps. Statistics must not inject `StepsRepository` or access the `step_measurements` collection directly.

Statistics calculates on read for the MVP. Do not create precomputed daily/weekly aggregate collections, aggregate repositories/models, background jobs, scheduled refresh, cache or a cross-metric generic aggregation framework.

The proposed Statistics read contract is maintained in [Steps API Contract](../api/metrics/steps.md#steps-statistics-apis--proposed-read-contract). It represents buckets with no accepted events as `steps: null` and `eventCount: 0`, rather than writing zero-marker documents. A bucket with accepted events has a positive sum because zero and negative increments are rejected at ingestion. `totalSteps` is null when the whole queried range has no accepted events.

If precomputation becomes necessary later, Statistics owns the aggregate schema, persistence and consistency lifecycle under ADR-001.

## 7. Retention and explicit non-goals

- Keep accepted raw measurement documents for the MVP; no TTL index or scheduled deletion.
- Cross-device duplicate detection and reconciliation.
- Device registry, registration, revocation, metadata or ownership verification.
- Cumulative counter snapshots, reset detection or difference calculation.
- Exact per-step timestamp distribution or interval-coverage tracking.
- Heartbeat / Producer-online status and proof of continuous measurement coverage.
- Calories, distance, pace, cadence, goals or clinical interpretations.
- Precomputed aggregate persistence and background processing.
