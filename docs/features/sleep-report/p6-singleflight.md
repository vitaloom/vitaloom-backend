# P6-SINGLEFLIGHT delivery

Scope: coalesce equal concurrent Sleep Story and Explanation requests within one Backend service instance. The [API contract](api-contract.md) owns request/response behavior. This delivery does not declare the full P6 phase complete.

## Implementation and rationale

- Use separate instance-owned concurrent maps of futures for Story and Explanation; no new infrastructure or provider configuration.
- Reuse the existing owner/evidence/policy/prompt cache identity, including exact windows, the complete Story pool or single Explanation code, and selected reference zone. Add consent revision to flight identity so revoke/re-enable cannot join prior work.
- Share the operation from cache lookup through publication and cooldown recording. Remove the flight in `finally`, including failure. A later request can regenerate after failure.
- Waiters have their own monotonic request budget and validation reserve. They do not cancel the shared future on timeout/interruption. Before returning every caller, including the leader, rereads evidence and consent; changed Story evidence produces current deterministic fallback, while changed Explanation evidence produces `STALE` or `INELIGIBLE`. Withdrawal suppresses content.
- Preserve provider retry, output validation, cache TTL, cooldown and deadline policies. No public transport or persistence schema changes.

## Verification and handoff

Planned checks: focused service/controller/concurrency/cooldown regression tests, plus the distinct HTTP → real Statistics → deterministic fake-provider integration suite. Concurrency tests hold the provider with latches and observe a waiter blocked on the shared future before releasing it. Test data is synthetic; no paid provider is used.

Executed from the Backend checkout on 2026-11-16:

| Command | Observed result |
| --- | --- |
| `mvn -o '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest' test` | Initial run: 30 tests, one failure. The concurrent-publication regression assumed independent generation for equal evidence; its second request now uses changed evidence, retaining its cooldown race assertion. |
| `mvn -o '-Dtest=SleepAiSingleFlightTest' test` | Worker run initially found a duplicate local test variable; corrected. Subsequent report: seven tests passed before the interruption case was added. The complete eight-test version is included in the combined run below. |
| `mvn -o '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest,SleepAiSingleFlightTest,SleepAiCooldownTest,RedisSleepAiCacheTest' test` | Exit 0: 51 tests, zero failures/errors/skips. Covers equal Story/Explanation calls, account/evidence separation, provider-error cleanup, withdrawal and absence of cache writes for both operations, consent revision separation, and waiter interruption without cancelling the leader. Existing deadline, cooldown and cache-adapter regressions pass. |
| `mvn -o '-Dtest=SleepAiEvidenceIntegrationTest' test` | Exit 0: eight tests, zero failures/errors/skips. Includes simultaneous authenticated HTTP requests through the actual Statistics calculator to a fake provider: exactly one provider call for each endpoint. Existing stale evidence and HTTP compatibility checks pass. |
| `git -c safe.directory=D:/prj/vita-loom/vitaloom-backend diff --check` | Exit 0; no whitespace errors. |

The orchestrator implemented the service, HTTP integration and owner docs; one context reader supplied discovery and one test worker supplied the new concurrency suite. The orchestrator inspected those changes and ran the final combined and integration commands. No implementation task remains blocked. Confidence is high for this scoped behavior based on 59 passing tests. Full Maven verification, live Redis/Mongo integration and production provider checks were not run for this in-memory-only change.

Next action: the queue runner stages and commits the scoped diff, then performs independent review and integration. Changes remain unstaged for that handoff.

## Limits

Flights and cooldowns are local to one service instance. There are no distributed locks or coordination across instances. Cache commands already dispatched cannot be rolled back by cancellation, as documented by P6-DEADLINE. Mobile focus refresh and production activation are outside this task. No production provider, production data, runner files, Business task records or phase ledger are changed.

## Independent review repair · 2026-11-16

The preceding review found a withdrawal window after the post-provider consent read but before asynchronous cache dispatch. The dispatch guard now rereads the exact consent revision within the remaining deadline. Cooldown recording also requires current consent. The leader applies the same final evidence/consent revalidation as waiters before completing the shared future and returning. If the final validation has no budget, content is suppressed with `CONSENT_REQUIRED`; deadline regressions now explicitly assert empty content while retaining the concurrent-success cooldown assertion.

One context reader confirmed the source map and finding; the orchestrator implemented and verified this repair directly. Four parameterized synthetic concurrency cases cover Story and Explanation withdrawal immediately before the cache dispatch guard and immediately after the cache operation. Both leader and waiter suppress content; pre-dispatch withdrawal performs no underlying cache write; re-enabling consent allows a fresh provider call, proving no retained flight or withdrawal-triggered cooldown.

Repair checks executed from the Backend checkout:

| Command | Observed result |
| --- | --- |
| `mvn -o '-Dtest=SleepAiSingleFlightTest#consentWithdrawalAtCachePublicationSuppressesLeaderAndWaiter' test` | Before repair: exit 1, all four new cases reproduced the leader returning generated content after withdrawal. |
| `mvn -o '-Dtest=SleepAiSingleFlightTest,SleepStoryServiceTest,SleepStoryControllerTest,SleepAiCooldownTest,RedisSleepAiCacheTest' test` | First repair run: 55 tests, two failures due to older deadline response expectations; the four new cases passed. Updated those expectations to require content-free final-validation failure. Final run: exit 0, 55 tests, zero failures/errors/skips. |
| `mvn -o '-Dtest=SleepAiEvidenceIntegrationTest' test` | Exit 0, eight tests, zero failures/errors/skips. Equal simultaneous authenticated requests still invoke the fake provider once per operation. |
| `git -c safe.directory=D:/prj/vita-loom/vitaloom-backend diff --check` | Exit 0, no whitespace errors. |

Confidence is high for the reviewed race based on its reproduction and 63 passing final tests. Full Maven verification and live Redis/Mongo checks were not run for this repair. Already dispatched cache commands retain the documented cancellation limitation; existing cache entries remain gated by current consent. The runner owns the next independent review, staging, commits and integration. Source and owner documentation changes remain unstaged.
