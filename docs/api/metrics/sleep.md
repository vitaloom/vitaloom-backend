# Sleep API Contract

The [Sleep Journal F1 API](../../features/sleep-journal/contract-reconciliation.md) is implemented under `/api/sleep/v2/sessions`. It stores one Main Sleep per account and statistical day, supports backfill through today in the current Vietnam calendar week, and permits editing or deleting only the latest Main Sleep in that week. The contract covers RANGE and DURATION inputs, mutation eligibility and errors.

## Legacy daily contract

**Status:** Documented design. No controller implements this endpoint in the current backend checkout.

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

## MVP acceptance checks

- [ ] Sleep upserts one record for the current local day.
