# VitaLoom Backend — API Contract v1

**Status:** Canonical MVP API behavior contract
**Daily timezone:** `Asia/Ho_Chi_Minh`  
**Timestamp format:** ISO 8601 with an explicit timezone offset. Producers should send UTC timestamps ending in `Z`.

This contract defines the target local-MVP API baseline for VitaLoom Backend and its Mobile and Data Producer consumers. It is the canonical API reference for new backend implementation. It is not a production multi-user API specification, and a listed endpoint is not considered implemented until its code is present and verified in this repository.

## 1. Conventions

- API paths use the `/api` prefix. The backend local-development Compose stack exposes MongoDB and Mongo Express; Spring Boot runs directly on the host. The former demo's Nginx gateway is not required by this contract.
- JSON request and response bodies use `Content-Type: application/json`.
- `occurredAt` records when a measurement or user action happened. The backend adds `receivedAt` when it receives an ingestion batch; the same receipt instant is assigned to newly accepted measurements in that batch.
- Persist event instants as UTC. Java code uses `Instant`; MongoDB BSON Date stores millisecond precision.
- Keep raw events/records as the source of truth. Spring Boot is responsible for validation, persistence and aggregation; clients and the producer must not calculate and submit authoritative daily totals.
- A missing metric is represented by `null`, not zero.
- For the MVP, a day starts at 00:00 in `Asia/Ho_Chi_Minh`. The backend derives the local statistics date from the original `occurredAt`.
- Clients/producers create stable IDs for retryable ingestion records and must reuse the same ID when retrying that record. The Water Tracking MVP in section 4 does not yet support safe POST replay.
- Spring Boot enforces uniqueness, backed by MongoDB unique indexes. An API gateway, if present, does not create business IDs or decide whether a record is a duplicate.
- A retry with an already accepted ID must not create a second record or double-count a metric.
- Basic authentication is specified in section 11. Water Tracking routes are authenticated and account-scoped; other metric APIs remain public and lack per-user data isolation. Personal-data ingestion must derive account ownership from an authenticated principal; do not accept `accountId` from the request body. Do not use the other metric APIs for production multi-user data until isolation is implemented.
- The Heart Rate persistence schema and indexes are defined in [Heart Rate Data Model & Persistence Design](../architecture/heart-rate-data-design.md).

## 2. Health Check

### `GET /api/health`

Successful response — `200 OK`:

```json
{
  "status": "UP"
}
```

## 3. Daily Dashboard Statistics

### `GET /api/statistics/daily`

Returns metric values for the current date in `Asia/Ho_Chi_Minh`.

Successful response — `200 OK`:

```json
{
  "date": "2026-10-24",
  "timezone": "Asia/Ho_Chi_Minh",
  "dailyAverageHeartRateBpm": 92,
  "steps": 1840,
  "sleepHours": 7.5,
  "waterMl": 1200
}
```

| Field | Type | Definition |
|---|---|---|
| `date` | string | Current local date, `YYYY-MM-DD` |
| `timezone` | string | Timezone used for the day's boundaries |
| `dailyAverageHeartRateBpm` | number or `null` | Unweighted arithmetic mean of accepted Heart Rate measurements by `occurredAt` within the day; `null` if no measurement exists |
| `steps` | integer or `null` | Sum of accepted step increments within the day; `null` if no step event exists |
| `sleepHours` | number or `null` | Sleep duration stored for the day |
| `waterMl` | integer or `null` | Sum of accepted water-intake records within the day; `null` if none exist |

The Heart Rate value follows AHM's Daily Average policy. It is not the latest observation. If the product later needs a latest/live observation, it must be represented as a separate field and must not be labelled as Daily Average.

The response is a read model. It may combine results from multiple metric-specific records; it does not imply the underlying metrics use the same write or processing logic.

## 4. Water Intake Tracking

The authenticated Water MVP uses `GET /api/water/daily-summary`, `GET /api/water/weekly-progress`, and `POST /api/water/logs`. The former proposed `POST /api/water-intakes` contract was never implemented and is superseded for this feature. See [the Water API contract](../features/water/api-contract.md) for query parameters, payloads, validation, ownership, and errors.

Water logs are separate from the future daily dashboard statistics read model described in section 3. No `/api/statistics/daily` implementation or integration is implied by these routes. Unlike the other metric APIs described here, these Water routes require an authenticated account and restrict records by account ID.

The Water create request has no client-generated idempotency key in this MVP. Clients must guard against double submission and must not automatically retry a POST whose outcome is unknown; a later idempotency contract is required before safe replay.

## 5. Sleep

### `PUT /api/sleep/daily`

Creates or replaces the current day's sleep-duration record. The MVP supports one sleep record per local calendar day.

Request:

```json
{
  "sleepHours": 7.5
}
```

Rules:

- The backend determines the current date in `Asia/Ho_Chi_Minh`; clients do not supply a timezone-dependent date for the MVP.
- `sleepHours` must be between 0 and 24 inclusive. Values above 24 are invalid.
- The record is uniquely identified by its statistics date. Repeating the request updates the same daily record rather than creating a second one.
- Updating the record is intentional replacement, not accumulation.
- Overnight sleep-session attribution is deferred. This MVP stores a duration for the current statistics day.

Successful response — `200 OK`:

```json
{
  "date": "2026-10-24",
  "timezone": "Asia/Ho_Chi_Minh",
  "sleepHours": 7.5
}
```

## 6. Steps

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

## 7. Heart Rate Batch Ingestion

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

### Request fields

| Field | Type | Required | Rule |
|---|---|---:|---|
| `deviceId` | string | Yes | Stable identifier for the device; reuse it across Producer runs. One batch contains measurements from one device only. |
| `runId` | string | Yes | Identifies the Producer run for diagnostics; it is not part of measurement identity. |
| `events` | array | Yes | Contains 1–500 measurements per request. |
| `events[].eventId` | string | Yes | Stable unique measurement ID within the account/device. Reuse it for every retry of that measurement. |
| `events[].occurredAt` | ISO 8601 timestamp | Yes | Device measurement time. Producers should send UTC with `Z`; Backend parses it as an `Instant`. |
| `events[].heartRateBpm` | integer | Yes | Must be between 1 and 300 inclusive. This is an input sanity boundary, not a clinical normal range. |

Additional rules:

- `accountId` is not accepted in the request. Backend must resolve it from the authenticated principal when Heart Rate ingestion is implemented. Basic Authentication now exists, but Heart Rate ingestion and account isolation are still prerequisites for a real multi-user deployment.
- `deviceId` is a source identifier, not an authentication credential. The MVP has no Device registry or device-registration workflow.
- `eventId` is the primary idempotency key for an individual measurement. The Producer generates it once and preserves it across retries.
- `occurredAt` is authoritative for history and statistics-day assignment. Out-of-order delivery is allowed.
- Multiple measurements may have the same `occurredAt`, including measurements from different devices. Timestamp is not a unique key.
- Backend creates `receivedAt`; clients cannot set or override it.
- Persist accepted items to the separate `heart_rate_measurements` collection. Do not store Heart Rate and Steps in a generic shared event collection.
- The measurement document, unique index and history index are defined in [Heart Rate Data Model & Persistence Design](../architecture/heart-rate-data-design.md).

### Partial-acceptance response

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

## 8. Duplicate and Error Handling

### Record identity

- Heart Rate measurements use `accountId + deviceId + eventId` as their unique persisted identity.
- Steps continues to use its existing per-event `eventId` contract; its metric-specific persistence design is separate.
- Water Tracking creates server-assigned log IDs and has no safe POST replay in this MVP.
- Retrying a supported retryable ingestion record must reuse its ID.
- The backend must enforce uniqueness in persistence, not rely only on a check-then-insert query.
- Identical retries of supported retryable ingestion records must never double-count a metric. Conflicting reuse of an ID must remain distinguishable from an identical retry.

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

Heart Rate item-level ID conflicts are represented in the partial-acceptance result as `REJECTED / EVENT_ID_CONFLICT`, so valid items in the same batch can still be accepted. Other metric-specific conflict behavior remains defined in its section. Water Tracking has no client ID conflict response in this MVP.

## 9. MVP Acceptance Checks

- [ ] Health check returns `200 OK` from Spring Boot at `GET /api/health`.
- [ ] Dashboard reads all four metrics in one request and returns `null` for missing metrics.
- [x] Water Tracking creates separate authenticated records and sums them for the requested local day.
- [ ] Sleep upserts one record for the current local day.
- [ ] Steps endpoint accepts raw increments and calculates daily totals without counting duplicate events twice.
- [ ] Heart Rate endpoint accepts one device per batch and stores each accepted measurement in `heart_rate_measurements`.
- [ ] Heart Rate Daily Average is the unweighted mean of accepted measurements by `occurredAt`, not the latest observation.
- [ ] Heart Rate batch responses expose per-item accepted, duplicate and rejected outcomes.
- [ ] Heart Rate retries reuse stable `eventId` values; duplicate and concurrent writes do not create a second measurement.
- [ ] MongoDB unique indexes prevent concurrent duplicate inserts.
- [ ] Dashboard and other existing metric daily boundaries use `Asia/Ho_Chi_Minh`, starting at 00:00. Water Tracking uses its required query timezone.
- [ ] Raw event timestamps are preserved independently of server receipt time.
- [ ] Expo displays the dashboard and can submit sleep/water inputs.

## 10. Explicitly Deferred

User-specific health data isolation outside Water Tracking, device registration/management, history pagination, personalized daily cutoff times, overnight sleep-session attribution, HTTP-level idempotency keys, batch-result replay storage, asynchronous processing, advanced analytics, load-generation orchestration and production-grade rate limiting are outside this contract's current implementation scope. Authentication beyond section 11—including verification, MFA, rate limiting, refresh rotation/revocation and device management—is deferred. Revisit the relevant items before a real multi-user deployment or physical wearable integration.

## 11. Basic Authentication MVP

The implementation baseline for this section is `note/authentication-v1/03-api-contract-auth.md`. All endpoints use JSON. The authenticated user shape is `{ "id": "<Mongo id>", "email": "name+tag@example.com" }`. Email is trimmed at the edges and compared case-insensitively; dots and plus tags are retained. Passwords are never trimmed, and registration requires at least eight characters.

| Endpoint | Auth | Success | Failure |
| --- | --- | --- | --- |
| `POST /api/auth/register` `{ "email": "...", "password": "..." }` | Public | `201` `{ "user": {"id":"...","email":"..."}, "accessToken":"...", "refreshToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `409 EMAIL_ALREADY_REGISTERED` |
| `POST /api/auth/login` `{ "email": "...", "password": "..." }` | Public | `200` same token and user shape as register | `400 VALIDATION_ERROR`; `401 INVALID_CREDENTIALS` |
| `POST /api/auth/refresh` `{ "refreshToken": "..." }` | Public | `200` `{ "accessToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `401 INVALID_REFRESH_TOKEN` |
| `GET /api/auth/me` | Bearer access JWT | `200` `{ "id":"...", "email":"..." }` | `401 UNAUTHORIZED` |

If registration persisted the account but token issuance fails, return `201` `{ "user": {"id":"...","email":"..."}, "authStatus":"LOGIN_REQUIRED" }`. The client offers login rather than retrying registration. Error responses use `{ "error": { "code": "...", "message": "..." } }`. Login uses the same error for unknown email and wrong password.

Access JWTs expire after 15 minutes; refresh JWTs after 30 days. They use separate signing keys from environment configuration and distinct token-type claims. A refresh JWT is never accepted as a Bearer access credential. Refresh returns only a new access JWT and does not rotate the refresh JWT. `GET /api/health` stays public. Water Tracking routes are authenticated and account-scoped; other metric endpoints remain public and unisolated.
