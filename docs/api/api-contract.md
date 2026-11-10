# VitaLoom Backend — API Contract Index

**Status:** Canonical entry point for the MVP API contracts  
**Daily timezone:** `Asia/Ho_Chi_Minh`  
**Timestamp format:** ISO 8601 with an explicit timezone offset. Producers should send UTC timestamps ending in `Z`.

This file is the navigation and shared-conventions entry point. Detailed endpoint contracts are maintained in focused documents by API area and metric. A documented endpoint describes the intended contract; it is not considered implemented until code is present and verified in this repository.

## 1. Contract map

| Area | Contract |
|---|---|
| Authentication | [Authentication API](./authentication.md) |
| Health check | `GET /api/health` — described below |
| Cross-metric dashboard | [Dashboard Statistics API](./dashboard-statistics.md) |
| Heart Rate | [Heart Rate API](./metrics/heart-rate.md) |
| Steps | [Steps API](./metrics/steps.md) |
| Sleep | [Sleep API](./metrics/sleep.md) |
| Water | [Water API](./metrics/water.md) |

## 2. Shared conventions

- API paths use the `/api` prefix. The backend local-development Compose stack exposes MongoDB and Mongo Express; Spring Boot runs directly on the host. The former demo's Nginx gateway is not required by this contract.
- JSON request and response bodies use `Content-Type: application/json`.
- `occurredAt` records when a measurement or user action happened. The backend adds `receivedAt` when it receives an ingestion batch; the same receipt instant is assigned to newly accepted measurements in that batch.
- Persist event instants as UTC. Java code uses `Instant`; MongoDB BSON Date stores millisecond precision.
- Keep raw events/records as the source of truth. Spring Boot is responsible for validation, persistence and aggregation; clients and the producer must not calculate and submit authoritative daily totals.
- A missing metric is represented by `null`, not zero. For Steps, an empty statistics bucket is `steps: null` and `eventCount: 0`; it does not prove zero activity or complete data coverage.
- For the MVP, a day starts at 00:00 in `Asia/Ho_Chi_Minh`. The backend derives the local statistics date from the original `occurredAt`.
- Clients/producers create stable IDs for their own retryable write records and must reuse the same ID when retrying that record.
- Spring Boot enforces uniqueness, backed by MongoDB unique indexes. An API gateway, if present, does not create business IDs or decide whether a record is a duplicate.
- A retry with an already accepted ID must not create a second record or double-count a metric.
- Authentication and route-level access requirements are documented in [Authentication](./authentication.md). Heart Rate batch ingestion and all Heart Rate Statistics routes require a Bearer access JWT and scope data to its subject. The Steps ingestion and Statistics contracts also require a Bearer access JWT and account scoping, but Steps endpoints are not implemented yet. Sleep/Water routes and the cross-metric daily dashboard do not yet enforce equivalent per-user access and isolation. Do not use unisolated paths for real multi-user private health data. Personal-data ingestion must derive account ownership from an authenticated principal; do not accept `accountId` from the request body.
- Metric-specific persistence schemas and indexes are documented under [Architecture](../architecture/). Heart Rate and Steps have separate raw-data designs: [Heart Rate Data Model & Persistence Design](../architecture/heart-rate-data-design.md) and [Steps Data Model & Persistence Design](../architecture/steps-data-design.md).

## 3. Health Check

### `GET /api/health`

Successful response — `200 OK`:

```json
{
  "status": "UP"
}
```

## 4. Duplicate and Error Handling

### Record identity

- Heart Rate measurements use `accountId + deviceId + eventId` as their unique persisted identity.
- Steps events use `accountId + deviceId + eventId` as their unique persisted identity.
- Water intake uses `intakeId` for each drinking action.
- Retrying the same logical record must reuse its ID.
- The backend must enforce uniqueness in persistence, not rely only on a check-then-insert query.
- Identical retries must never double-count a metric. Conflicting reuse of an ID must remain distinguishable from an identical retry.

### Generic error response

Use a consistent error shape for request-level errors:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The request payload is invalid."
  }
}
```

Water's single-record ID conflict continues to return `409 Conflict`. Heart Rate and Steps item-level ID conflicts are represented in the partial-acceptance result as `REJECTED / EVENT_ID_CONFLICT`, so valid items in the same batch can still be accepted. Other metric-specific conflict behavior remains defined in its section.

## 5. Cross-cutting deferred capabilities

Per-user access and data isolation for Sleep/Water and the cross-metric daily dashboard, device registration/management, history pagination, personalized daily cutoff times, overnight sleep-session attribution, HTTP-level idempotency keys, batch-result replay storage, asynchronous processing, advanced analytics, load-generation orchestration and production-grade rate limiting are outside this contract's current implementation scope. Revisit the relevant items before a real multi-user deployment or physical wearable integration.

## 6. Cross-cutting acceptance checks

- [ ] Health check returns `200 OK` from Spring Boot at `GET /api/health`.
- [ ] API failures use the documented error envelope and do not disguise retrieval failures as successful empty data.
- [ ] Raw event timestamps remain separate from server receipt timestamps.
- [ ] The Mobile and Data Producer consumers are checked when a contract changes.
