# Sleep Journal F1 API contract

The backend provides authenticated, account-scoped Sleep Journal CRUD at `/api/sleep/v2/sessions`. Owner identity comes from the validated JWT subject. The API uses the existing shared RFC 9457 Problem Details response format.

| Operation | Request | Response |
| --- | --- | --- |
| POST /api/sleep/v2/sessions | SessionWrite | 201 SessionResponse |
| GET /api/sleep/v2/sessions?from=&to= | Optional inclusive statistical day bounds | 200 {sessions: SessionResponse[]} |
| GET /api/sleep/v2/sessions/{id} | Sleep session ID | 200 SessionResponse |
| PUT /api/sleep/v2/sessions/{id} | SessionWrite | 200 SessionResponse |
| DELETE /api/sleep/v2/sessions/{id} | Sleep session ID | 204 No Content |

SessionWrite uses uppercase MAIN/NAP, RANGE/DURATION and optional COMFORTABLE/NORMAL/TIRED feeling. DURATION uses integer minutes and an endDay without time fields. RANGE uses an IANA timeZone and start/end civil values, with optional explicit UTC offset choice for repeated local times. The server resolves absolute instants and elapsed minutes and stores the wake day in the chosen zone. All durations must be whole minutes from 1 through 1440. Future records are rejected. Multiple entries per statistical day are supported. Range overlap is checked during the request and adjacent half-open ranges are accepted.

MongoDB stores SleepSession documents in `sleep_sessions` with snake_case fields. The API DTO stays separate from that document. Ordinary MongoDB reads and writes support a standalone deployment. MongoDB availability and application credentials must be configured as for the existing backend.

## Error responses

- `VALIDATION_ERROR` (400) for malformed query parameters.
- `SLEEP_VALIDATION` (422) for invalid session data.
- `SLEEP_OVERLAP` (409) for an overlapping range.
- `SLEEP_NOT_FOUND` (404) for an absent record or a record owned by another user.
- `UNAUTHORIZED` (401) when credentials are missing or invalid.

## Deferred — Post-MVP

- Transaction-based concurrency guarantees
- Optimistic locking
- Idempotency keys
- Operation receipts
- Unknown-outcome reconciliation
- Cross-device conflict resolution
- Offline synchronization

The simplified MVP accepts last-write-wins updates and does not guarantee that concurrent range creations cannot overlap. The API does not automatically retry failed writes. A client reports success only after it receives a successful response. The approved F1 business rules remain unchanged. These temporary CRUD limitations apply only to this implementation stage.
