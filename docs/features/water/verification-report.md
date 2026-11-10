# Water Intake MVP — Verification Report

Date: 2026-11-06 (Asia/Saigon). Only checks actually executed are marked PASS.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| Backend tests and build | PASS | `mvn -DforkCount=0 package`: 21 tests, 0 failures/errors, `BUILD SUCCESS`. Forking was disabled because Mockito agent attach hung in this sandbox. The Maven wrapper could not write its distribution cache under the user profile, so installed Maven was used. |
| FE domain, adapter and auth refresh tests | PASS | `node --test tests/water-domain.test.cjs tests/water-api-repository.test.cjs tests/auth-refresh.test.mjs`: 12 passed, 0 failed. Node emitted the existing `MODULE_TYPELESS_PACKAGE_JSON` warning. |
| FE typecheck | PASS | `.\\node_modules\\.bin\\tsc --noEmit`: exit 0. |
| FE lint | PASS | `npm run lint`: exit 0. |
| FE web build | PASS | `.\\node_modules\\.bin\\expo export --platform web --output-dir water-api-export-check`: exit 0; output directory removed afterward. |

## Local backend + MongoDB integration

A temporary Java 21 container ran the built backend on port 6970 against the existing healthy MongoDB Compose service. Requests used newly registered test accounts and Bearer access tokens. The temporary backend container was stopped after verification; MongoDB and its test records remain in the local development volume.

| Scenario | Result | Observed evidence |
| --- | --- | --- |
| Unauthenticated read | PASS | GET daily-summary returned `401 UNAUTHORIZED`. |
| Empty day | PASS | New account received `totalMl: 0`, `logs: []`. |
| Create and daily read | PASS | POST created a log with ID; the requested day returned one log, `totalMl: 250`, `percent: 12.5`. |
| Timezone boundary | PASS | A `2026-11-05T23:30:00Z` log appeared on `2026-11-06` in `Asia/Ho_Chi_Minh` and on `2026-11-05` in `UTC`. |
| Weekly consistency | PASS | Seven days returned; Friday total matched daily total at 250 ml. |
| Account isolation | PASS | A second authenticated account received zero for the first account's log day. |
| Server validation | PASS | 2001 ml, future timestamp, timestamp without offset, and non-Monday week start each returned HTTP 400. |
| Integer JSON type | PASS | Fractional `250.5`, string `"250"`, and boolean `true` amounts returned 400; integer `250` returned 201. |
| Backend restart persistence | PASS | After restarting the temporary backend container, the original account still read one log and 250 ml from MongoDB. |

## Not executed

- Physical device or emulator walkthrough of Water Landing, Quick Add, Detailed Entry, and Progress against the live backend. The FE adapter and flows were checked by automated tests and web export, but tap interaction and network error display on a device were not observed.
- Explicit 5xx/network failure injection on the live mobile UI. Adapter tests verify rejected POST propagation and no revision update after failure; the live UI was not run.
- A dedicated Mongo repository integration test in the Maven suite. The live HTTP checks exercised the real repository and uncovered a derived-query failure that was fixed with an explicit Mongo range query before the passing run.
