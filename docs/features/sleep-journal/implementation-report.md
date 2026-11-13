# Main Sleep implementation report

Requirements baseline is the [merged business PR #28](https://github.com/vitaloom/vitaloom-business-docs-and-tools/pull/28) at merge commit `2b0b1de`. Implementation uses the existing `feat/sleep-tracking-api` and `sleep-tracking-flow` checkouts.

## Implementation and API impact

| Work | Owner | Delivery |
| --- | --- | --- |
| Repository discovery | Context reader | Traced both indexed checkouts and the existing fixture analytics and tests |
| Backend CRUD | Backend worker | Updated validator, service, Mongo repository, and response mapper while reusing JWT ownership, the controller paths and Problem Details |
| Mobile Journal | Mobile Journal worker | Updated Entry, Journal, Detail, Edit, confirmation, and shared repository while reusing routes, controls and focus-based refresh |
| Mobile analytics | Mobile analytics worker | Updated existing fixture calculations and removed the Nap insight filter |
| Integration | Orchestrator | Added Mongo/HTTP scenarios, updated the Dashboard Sleep tile and documented the handoff |

The [API contract](contract-reconciliation.md) covers required `MAIN` writes, additive `editable`, optional `If-Match` using `confirmedAt`, and Problem Details codes. Latest is the greatest stored Main Sleep statistical day. Reads preserve legacy NAP rows as read-only. The current week uses the server clock in `Asia/Ho_Chi_Minh`. RANGE attribution remains the wake day in its stored reference timezone.

There is no Sleep implementation in the backend Statistics service in this checkout. Mobile Sleep Home and the Dashboard tile use the shared Journal-backed daily read. Weekly averages, Goal progress, INS-S01–S04, A/B/C, and versioned AI evidence are updated in the existing mobile development fixture. Production endpoints for those features are outside this change.

## MongoDB deployment

`uk_sleep_owner_main_day` is a partial unique index on `{owner: 1, statistical_day: 1}` with `{sleep_type: "MAIN"}`. The repository ensures the index at startup. It protects uniqueness across concurrent writers. Same-account eligibility and overlap checks are serialized within a single backend process. Deploy this MVP with a single backend writer instance to preserve those cross-record guarantees. Conditional MongoDB operations protect updates and deletes of the same record.

Audit the deployment database before rollout using these read-only queries in `mongosh`:

```js
db.sleep_sessions.countDocuments({sleep_type: "NAP"})
db.sleep_sessions.aggregate([
  {$match: {sleep_type: "MAIN"}},
  {$group: {_id: {owner: "$owner", day: "$statistical_day"}, count: {$sum: 1}}},
  {$match: {count: {$gt: 1}}}
])
```

Duplicate Main Sleep rows block index creation and application startup. Preserve an export of affected rows and have the data owner approve which record belongs in the live Journal for each conflict before applying a separately reviewed migration. Re-run the audit, then verify that the application starts and the unique index exists. Legacy Nap rows do not participate in the partial index. No production database was accessed or migrated.

## Changed files

- Backend Sleep controller, `SessionResponse`, `SleepSession`, `SleepSessionRepository`, `SleepJournalService`, `SleepJournalServiceImpl`, and `SleepSessionValidator`.
- Backend `SleepJournalMongoIntegrationTest` and `SleepSessionValidatorTest`, and Sleep API and deployment documents.
- Mobile Sleep HTTP and fixture repositories, types, date/validation/navigation utilities, localized copy, picker and notice components, Entry/Journal/Detail/Home and Insight screens, fixture analytics, and Sleep tests. The Dashboard changes are in Home screen, metric card, messages and metric definitions.

## Validation mapping

| Business rules | Scenarios | Evidence |
| --- | --- | --- |
| BR-F1.1–7, 10, 14, 17 | VAL-SLT-01–06, 14–15 | Mongo/HTTP and validator tests cover Main-only, duration bounds, optional feelings, duplicate day across methods, future/past days, concurrent requests, direct unique-index rejection and cross-day RANGE overlap |
| BR-F1.7, 12–13, 18–19 | VAL-SLT-07–13 | Mongo/HTTP tests cover backfill as read-only, latest-only edit/delete, moving to a free or occupied day, recalculating the latest, Sunday-to-Monday rollover, ownership and stale writes |
| BR-F1.8–11 | VAL-SLT-06, 15, 18 | Validator and Mongo/HTTP tests cover timezone preservation, time conversion, elapsed duration, repeated offsets and nonexistent local times |
| BR-F2.1–3, 9, 14–19, 32, 37 | VAL-SLT-16–19 | See the mobile handoff for Journal-backed daily reads and development fixture coverage |
| BR-F1 legacy | VAL-SLT-20 | Mongo integration verifies legacy Nap remains visible and read-only. A disposable collection with two same-day Main Sleep rows and a Nap returned duplicate key `11000`, created no index, and kept all three rows. Deployment data audit remains open. |

## Verification

- `mvn -o verify` against a disposable standalone MongoDB 8 instance with transactions disabled built the application and passed 78 tests, including 11 Sleep integration tests and 8 Sleep validator tests. The integration suite includes a real authenticated HTTP create/detail round-trip.
- Mobile verification and remaining device checks are recorded in the mobile implementation handoff.
