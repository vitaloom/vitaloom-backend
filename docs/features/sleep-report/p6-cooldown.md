# P6-COOLDOWN delivery · 2026-11-16

Scope: successful-generation cooldowns for the test-only deterministic Story and Explanation provider. Sources: the P6 hardening runbook, AI content guidelines and accepted P6-EVIDENCE/P6-CACHE publication checks. The runner owns Git operations and independent review; leave delivery unstaged. No live Gemini request or production data is involved.

## Plan and implementation

1. Context discovery: one context-reader used the current checkout's CodeGraph and identified `SleepStoryService` as the shared generation/publication boundary.
2. Service and owner docs: orchestrator. Keep cache lookup before cooldown, reuse current consent/evidence validation, record success only after validated publication and a successful cache write.
3. Controlled-time and representative HTTP integration tests: Luna worker `cooldown_tests`, with orchestrator inspection and final validation.

Story state is keyed by authenticated owner. Generation on a cache miss is blocked until 15 minutes after the last successful publication, except when the current reporting day is later than the last successful Story day. The reporting day comes from the Statistics-owned Recent period (`Asia/Ho_Chi_Minh`), independently of any selected A04 zone. An unsuccessful first attempt on a new day leaves the exemption available. After that day's successful generation, normal cooldown applies again.

Explanation state uses an owner/code pair and five minutes from successful publication. Evidence changes, version hints and A04 reference-zone changes cannot create a new cooldown identity. Story and Explanation state are independent. The shared injected `Clock` measures both intervals; generation is permitted at the exact expiry instant.

Cache hits still pass full publication checks and neither consume nor extend cooldown. Provider exceptions, invalid output, stale evidence, consent changes, and cache read/write failure do not record success. Cooldown requests recheck current evidence and consent, returning current deterministic `FALLBACK` content; Story includes a localized optional `notice`. No scheduler, expiry callback or provider retry was added.

Cooldown state is intentionally local to the current service instance, separate from evidence-keyed Redis content storage. A process restart clears state; multiple instances do not share it. This unit does not implement single-flight or prevent simultaneous cache-miss calls that begin before either publishes success. Those remain separate P6 work. State retains only owner/code keys and last-success timestamps/day, not provider content or raw health records.

## Validation and handoff

Tests use synthetic data, controlled clocks, deterministic fake providers and the operator-supplied disposable loopback Redis. The HTTP/Statistics fixture mocks the Sleep raw read and consent boundaries; MongoDB persistence and the JWT filter chain are outside that fixture.

| Executed command (Backend working directory) | Observed result |
| --- | --- |
| `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest' test` | Exit 0: 21 tests, no failures/errors/skips; main and test compilation passed. |
| `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dtest=SleepAiCooldownTest,SleepAiEvidenceIntegrationTest' test` | Final orchestrator run exited 0: 15 tests, no failures/errors/skips. Earlier worker attempts with the same command found a missing mutable-clock test helper and then a Mockito restubbing error; both fixture issues were corrected, followed by a 12-test passing worker run before the orchestrator expanded coverage. |
| `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dsleep.cache.redis.port=62915' '-Dtest=SleepAiCacheIntegrationTest' test` | Exit 0: all six real Redis HTTP/Statistics/fake-provider integration tests passed, no failures/errors/skips. Includes immediate cooldown fallback after evidence change/expiry/eviction, later generation after controlled expiry, cache reuse and consent/staleness regression. |
| `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dtest=Sleep*Test,!SleepAiCacheIntegrationTest,!SleepJournalMongoIntegrationTest,!SleepRecentReportMongoIntegrationTest,!SleepRecentReportErrorTest,RedisSleepAiCacheTest' verify` | Exit 0: 71 selected Sleep/Statistics/cache-adapter regression tests, no failures/errors/skips; JAR packaging and Spring Boot repackage passed. This selects the non-Mongo Sleep suite; real Redis ran separately above. |
| `Test-NetConnection -ComputerName 127.0.0.1 -Port 62915 -InformationLevel Quiet` | Returned True for the disposable Redis fixture. |
| `docker info --format '{{.ServerVersion}}'` | Docker daemon named-pipe access denied; Mongo-backed checks are unavailable in this session. |
| `git -c safe.directory=D:/prj/vita-loom/vitaloom-backend -C vitaloom-backend diff --check` | Exit 0, no whitespace errors. Final status contains only the six scoped source/test/docs paths; the index is empty and HEAD remains the assigned base on `task/sleep-p6-cooldown`. |

Controlled tests cover completion-time recording, exact 15/5-minute boundaries, current cache hits without extending cooldown, evidence changes, owner/code isolation, independent Story/Explanation state, invalid/failed/stale/consent-suppressed attempts, cache failures, and a failed then successful first generation across midnight. The HTTP path verifies current fallback fingerprints after feeling changes, an unaffected second insight, the Story notice, and no automatic provider call when the clock advances.

All implementation/test tasks are complete and the orchestrator inspected the integrated diff. Confidence is high for the scoped single-instance behavior based on the executed controlled-clock, HTTP/Statistics and real Redis checks. Full Mongo-backed application verification and native-device checks were not run; Docker access is denied and no Mobile behavior changed. The full backend suite is not claimed. Instance-local state and concurrent calls before publication remain the limits described above. Runner-owned independent review is still required before integration.

P6-EVIDENCE and P6-CACHE are prerequisites. This delivery covers only P6-COOLDOWN. P6-DEADLINE, P6-SINGLEFLIGHT, P6-FE-REFRESH and production-provider safety gates remain outside scope. The real provider remains disabled. No overall P6 production-readiness claim is made.

Next action: runner stages and commits the scoped delivery, then performs independent review against the exact commits before integration.
