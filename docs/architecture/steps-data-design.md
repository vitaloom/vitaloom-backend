# Steps Data Model & Persistence Design

**Status:** Draft — review required before implementation  
**Scope:** Raw step-event semantics, identity, MongoDB persistence and the query boundary to Statistics  
**Related API contract:** [Steps API Contract](../api/metrics/steps.md)  
**Architecture ownership:** [ADR-001 — Statistics Owns Aggregate Read Models](./decisions/ADR-001-statistics-owns-aggregate-read-models.md)

This design is specific to Steps. Do not generalize it into a shared Heart Rate / Steps event model or collection.

## 1. Confirmed semantics

The following behavior is established by the existing Steps contract or explicitly agreed during design:

- A Step event carries an increment (`stepsDelta`), not a cumulative counter snapshot.
- `stepsDelta` is a non-negative integer under the current API contract. Zero is distinct from a missing event.
- `occurredAt` is a point-in-time timestamp representing when an increment is completed and recorded.
- The entire `stepsDelta` is assigned to the time bucket and local calendar date containing `occurredAt`.
- The backend creates `receivedAt` when it receives the batch; it is not supplied by the Producer.
- Daily calendar boundaries use `Asia/Ho_Chi_Minh`.
- Accepted raw events remain the source of truth. Statistics calculates derived totals on read for the current MVP.

This model intentionally does not claim to know the per-step distribution inside an increment interval. An increment that spans midnight is assigned wholly to the bucket containing its `occurredAt`.

## 2. Proposed raw document

Proposed collection: `step_measurements`.

```json
{
  "_id": "ObjectId(...)",
  "accountId": "authenticated-account-id",
  "deviceId": "steps-device-001",
  "eventId": "steps-run-001-000001",
  "stepsDelta": 12,
  "occurredAt": "2026-11-10T01:00:10.000Z",
  "receivedAt": "2026-11-10T01:00:12.000Z"
}
```

| Field | Proposed type | Responsibility |
|---|---|---|
| `_id` | ObjectId | Internal MongoDB document identity |
| `accountId` | String | Account ownership; expected to come from authenticated principal |
| `deviceId` | String | Source/device attribution; proposed addition to current Steps batch envelope |
| `eventId` | String | Stable identity of one increment event, reused on retry |
| `stepsDelta` | Integer | Non-negative step increment |
| `occurredAt` | Instant / BSON Date | Increment completion/recording time |
| `receivedAt` | Instant / BSON Date | Backend batch receipt time |

`runId` remains batch diagnostic metadata and should not be part of a persisted event's identity. A separate Device collection or device-registration workflow is not proposed for the MVP.

## 3. Identity and idempotency — proposal

A stable event ID must prevent the same logical increment from being counted twice. MongoDB uniqueness is the final concurrency guard; an application-only pre-check is insufficient.

If `deviceId` is approved and event IDs are scoped per device, the candidate unique index is:

```javascript
db.step_measurements.createIndex(
  { accountId: 1, deviceId: 1, eventId: 1 },
  { unique: true, name: "uk_steps_account_device_event" }
)
```

The candidate time-range index is:

```javascript
db.step_measurements.createIndex(
  { accountId: 1, occurredAt: 1 },
  { name: "ix_steps_account_occurred_at" }
)
```

For an existing identity:

- Same normalized `occurredAt` and `stepsDelta`: classify as an identical retry.
- Different immutable payload: classify as an event ID conflict.
- `receivedAt` and `runId` do not participate in payload identity.

The unique-key scope must be finalized together with the `deviceId` decision. If event IDs are guaranteed unique account-wide, a different unique index may be preferable.

## 4. Device attribution and cross-device overlap

`deviceId` is proposed to identify where an increment stream originated. It is attribution metadata, not proof of device ownership and not a cross-device deduplication algorithm.

Cross-device duplicate detection/reconciliation is **out of MVP scope**:

- Different device IDs and event IDs are different source events.
- Similar timestamps or overlapping time windows do not prove that two increments represent the same steps.
- The backend must not automatically discard or merge increments merely because timestamps overlap.
- If the account later sends simultaneous Phone and Watch streams, a source-priority or reconciliation policy is required before blindly summing both streams.

The MVP source policy (one canonical Steps stream per account versus allowing multiple independent streams) remains an open decision.

## 5. Validation and event ordering — open decisions

The current API contract establishes a non-negative integer `stepsDelta`, stable `eventId`, and an ISO-8601 `occurredAt`. Before implementation, confirm:

- Whether `deviceId` is required in the batch envelope.
- Maximum events per batch and maximum accepted `stepsDelta`.
- Whether a future `occurredAt` is rejected, and whether to align with Heart Rate's strict no-clock-skew rule.
- Whether out-of-order delivery is accepted (recommended: yes).
- Whether the batch response keeps its current aggregate-count `202 Accepted` shape or adopts per-item outcomes like Heart Rate.

Do not silently copy Heart Rate's BPM validation or response details when they do not fit Steps.

## 6. Raw query boundary and Statistics ownership

The Steps feature owns raw increment ingestion, validation, event identity, persistence and raw-data query capabilities. Statistics owns all derived Steps totals and time-bucket calculations.

The intended service boundary exposes account-scoped raw events within a supplied half-open instant range `[startInclusive, endExclusive)`. Keep MongoDB query construction and persistence models inside Steps. Statistics must not inject StepsRepository or access the `step_measurements` collection directly.

Statistics calculates on read for the MVP. Do not create precomputed daily/weekly aggregate collections, aggregate repositories/models, background jobs, scheduled refresh, cache, or cross-metric generic aggregation framework.

If precomputation becomes necessary later, Statistics owns the aggregate schema, persistence and consistency lifecycle under ADR-001.

## 7. Suggested indexes and retention — proposal

- Unique identity index as finalized in section 3.
- Account/time-range index on `{ accountId: 1, occurredAt: 1 }`.
- No TTL index for the MVP; raw records remain available for recalculation.
- Do not add speculative sort or device indexes without a demonstrated query.

## 8. Explicit non-goals

- Cross-device duplicate detection and reconciliation.
- Device registry, registration, revocation or ownership verification.
- Cumulative counter snapshots, reset detection or difference calculation.
- Exact per-step timestamp distribution.
- Calories, distance, pace, cadence, goals or clinical interpretations.
- Precomputed aggregate persistence and background processing.
