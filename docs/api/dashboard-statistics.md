# Dashboard Statistics API Contract

**Status:** Canonical MVP contract  
**Timezone:** `Asia/Ho_Chi_Minh`

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

The Heart Rate value follows AHM's Daily Average policy; it is not the latest observation. The distinct `GET /api/statistics/heart-rate/summary` endpoint returns Latest Recorded Heart Rate together with selected-day Daily Average. This dashboard response remains unchanged.

The response is a read model. It may combine results from multiple metric-specific records; it does not imply the underlying metrics use the same write or processing logic.

## Security limitation

The cross-metric dashboard does not yet enforce equivalent per-user access and data isolation. Do not use it for real multi-user private health data until the dashboard and all contributing metric reads are account-scoped.

## MVP acceptance checks

- [ ] Dashboard reads all four metrics in one request and returns `null` for missing metrics.
- [ ] Expo displays the dashboard and can submit sleep/water inputs.
