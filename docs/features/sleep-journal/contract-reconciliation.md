# Sleep Journal F1 API contract

Sleep Journal uses the validated Bearer JWT subject as the owner and returns errors in the shared RFC 9457 Problem Details format. The base path is `/api/sleep/v2/sessions`.

| Operation | Request | Success |
| --- | --- | --- |
| POST | `SessionWrite` | 201 `SessionResponse` |
| GET | Optional inclusive `from` and `to` statistical days | 200 `{ "sessions": [...] }` |
| GET `/{id}` | Session ID | 200 `SessionResponse` |
| PUT `/{id}` | `SessionWrite`, optional `If-Match` | 200 `SessionResponse` `|`
| DELETE `/{id}` | Session ID, optional `If-Match` | 204 No Content |

Writes require `sleepType: "MAIN"` and `method: "RANGE"` or `"DURATION"`. The optional `feeling` accepts `COMFORTABLE`, `NORMAL`, `TIRED`, or `null`. `DURATION` takes `endDay` in `YYYY-MM-DD` format and integer `minutes`. `RANGE` takes an IANA `timeZone` and `start`/`end` objects with `day`, two-digit `hour` and `minute`, and an optional `offsetMinutes` for resolving repeated local times. The server validates the supplied offset against the zone and rejects missing or unresolved civil times. Durations are 1–1,440 whole minutes. For RANGE, the wake date in the reference timezone is the statistical day and elapsed time determines minutes. Editing a RANGE as RANGE preserves its reference timezone.

The server checks the mutation window using its clock in `Asia/Ho_Chi_Minh`. The statistical day must be between this week's Monday and today, inclusive. A day accepts one Main Sleep across both entry methods. RANGE sessions on different days must also have disjoint actual time intervals. Adjacent endpoints are allowed.

`SessionResponse` adds `editable`, computed using the account's greatest existing Main Sleep statistical day and the current mutation window. Filtering the list leaves this account-wide calculation intact. The response after a successful backfill or day change reflects eligibility after the write. Clients can use `editable` for UI affordances. The server rechecks eligibility on every write. Existing NAP rows can still be read and always return `editable: false`.

For PUT and DELETE, mobile sends the previously read `confirmedAt` as one quoted `If-Match` value. A changed version returns `SLEEP_STALE`. Older clients may omit the header. The server still guards each conditional write with the version it loaded for that request.

| HTTP | Problem `code` | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR`, `SLEEP_VALIDATION` | Invalid query, malformed request, or invalid `If-Match` |
| 401 | `UNAUTHORIZED` | Missing or invalid authentication |
| 404 | `SLEEP_NOT_FOUND` | Missing record or a record belonging to another account |
| 409 | `SLEEP_DAY_ALREADY_RECORDED` | A Main Sleep already occupies the target day. Use that day's Journal query to open it. |
| 409 | `SLEEP_WEEK_LOCKED` | The record or requested day is in a completed week |
| 409 | `SLEEP_RECORD_NOT_LATEST` | A different Main Sleep is latest, or the legacy record is read-only |
| 409 | `SLEEP_OVERLAP` | Actual RANGE times overlap another Main Sleep |
| 409 | `SLEEP_STALE` | The loaded record changed before the requested write |
| 422 | `SLEEP_VALIDATION` | Invalid type, method, date, duration, feeling, timezone, DST choice, or future time |

MongoDB stores the records in `sleep_sessions`. See the [implementation report](implementation-report.md) for the unique index, deployment audit, and verification results. The service uses ordinary MongoDB operations and supports a standalone database.
