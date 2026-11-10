# Water Intake API Contract

All paths below have the `/api` prefix and require `Authorization: Bearer <access-token>`. Data is scoped to the authenticated account. Successful responses are plain JSON objects; errors use `{ "error": { "code": "...", "message": "..." } }`. Dates are ISO local calendar dates (`YYYY-MM-DD`). `timeZone` is a required IANA zone ID, such as `Asia/Ho_Chi_Minh`, URL encoded in query strings. The backend computes day intervals in that zone and stores log instants in MongoDB as UTC dates. The timezone used for a historical query is the supplied query timezone; the MVP has no account timezone profile.

## GET `/api/water/daily-summary?date=2026-11-06&timeZone=Asia%2FHo_Chi_Minh`

Returns `200 OK` even when the day has no logs:

```json
{
  "date": "2026-11-06",
  "goalMl": 2000,
  "totalMl": 250,
  "percent": 12.5,
  "logs": [
    { "id": "log-id", "amountMl": 250, "loggedAt": "2026-11-06T02:15:00Z" }
  ]
}
```

`logs` are newest first. For an empty day, `totalMl` and `percent` are `0`, and `logs` is `[]`. `percent = totalMl / 2000 * 100` and may exceed 100; only the mobile visual ring is capped.

## GET `/api/water/weekly-progress?weekStart=2026-11-02&timeZone=Asia%2FHo_Chi_Minh`

`weekStart` must be a Monday in the supplied local timezone. Returns exactly seven ascending dates from Monday through Sunday, including zeros for empty days. Historical weeks are accepted.

```json
{
  "weekStart": "2026-11-02",
  "weekEnd": "2026-11-08",
  "days": [
    { "date": "2026-11-02", "totalMl": 0, "goalMl": 2000 },
    { "date": "2026-11-03", "totalMl": 0, "goalMl": 2000 },
    { "date": "2026-11-04", "totalMl": 0, "goalMl": 2000 },
    { "date": "2026-11-05", "totalMl": 0, "goalMl": 2000 },
    { "date": "2026-11-06", "totalMl": 250, "goalMl": 2000 },
    { "date": "2026-11-07", "totalMl": 0, "goalMl": 2000 },
    { "date": "2026-11-08", "totalMl": 0, "goalMl": 2000 }
  ]
}
```

The mobile client requests daily-summary when the user selects a chart day.

## POST `/api/water/logs`

Request for a Quick Add or an entry recorded now:

```json
{ "amountMl": 250 }
```

The server supplies `loggedAt` from its clock when the property is omitted. For a manually selected historical time, send `loggedAt` explicitly:

```json
{ "amountMl": 250, "loggedAt": "2026-11-06T09:15:00+07:00" }
```

`amountMl` must be an integer from 1 through 2000. An explicit `loggedAt` must be a string with a UTC marker or numeric offset and must not be later than server time. A missing `loggedAt` uses the server time captured while processing the request. An explicit `null`, non-string, malformed timestamp, timestamp without an offset, or future timestamp is invalid. The server obtains account identity from the authenticated principal, never from request fields. Success is `201 Created`:

```json
{ "id": "log-id", "amountMl": 250, "loggedAt": "2026-11-06T02:15:00Z" }
```

The response normalizes the instant to UTC. `source` remains UI metadata and is not sent or stored. A failed POST must not be shown as saved. There is no client idempotency key yet, so a connection failure after the server commits has an unknown outcome; the client does not automatically retry POST.

## Errors

`401` with code `UNAUTHORIZED` indicates missing, expired, or invalid authentication; the existing mobile auth client performs one refresh and retry when appropriate. `400` with code `VALIDATION_ERROR` indicates malformed date, non-Monday `weekStart`, missing/unknown timezone, invalid amount, timestamp without offset, or future timestamp. An explicitly future `loggedAt` returns `loggedAt cannot be in the future.` Errors use the backend's existing `{error:{code,message}}` envelope.
