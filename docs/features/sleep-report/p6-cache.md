# P6-CACHE delivery · 2026-11-16

Scope: separate Redis caches for the existing fake-provider Story and Explanation. Source requirements: the Business AI content guidelines (OW-08–42), P6 hardening runbook and DL-0006. No production provider or data was used. The continuation was resumed manually on the recorded task branch; runner checkpoint files were not edited.

## Implementation

`SleepStoryService` uses `SleepAiCache`, implemented by `RedisSleepAiCache` through Spring's string Redis template and typed Jackson records. Only validated successful Story/Explanation output is written. Redis atomically sets an absent key with a 24-hour expiry; hits never refresh retention and existing keys are never overwritten or deleted by the application. Consent withdrawal and evidence changes leave previous entries to expire naturally.

The distinct `sleep:ai:story:` and `sleep:ai:explanation:` namespaces contain a SHA-256 digest of owner, complete normalized evidence identity, explicit content-policy version and the corresponding prompt version. Story identity covers the complete READY pool, candidate policies, selected zone and all observation/baseline windows. Explanation identity covers its single READY code and full current Detail payload. Public Detail versions and HTTP response shapes are unchanged. Template/content-policy changes must advance their constants in `SleepStoryService`.

Every request checks current consent and eligibility before accessing Redis. Both hits and provider results then reread current evidence and consent revision, compare the complete identity/key and validate the output before publishing or writing. A changed consent revision suppresses all content, even after revoke/re-enable. Changed Story evidence yields current deterministic fallback or no content if ineligible; changed Explanation evidence yields STALE/INELIGIBLE. Invalid cached content is neither served, overwritten nor regenerated in the same request.

Redis read/decoding failure skips the provider and returns current deterministic fallback. Write failure also returns fallback. Fallbacks are never cached. Redis uses `REDIS_HOST` and `REDIS_PORT` (defaults localhost/6379), with connection and command timeouts of 500ms. Infrastructure is externally supplied; there are no schedulers, locks, invalidation deletes or cache administration endpoints. The fake-provider dev/test enablement and production disablement remain unchanged.

## Plan, ownership and acceptance

One context-reader identified the current service/publication boundary; no CodeGraph index existed in this checkout. The orchestrator owned service integration, safety tests, configuration, API documentation and final verification. Luna worker `redis_adapter` owned the cache interface/adapter/dependency and adapter tests. Luna worker `redis_integration` owned the initial Redis integration fixture; the orchestrator reviewed and extended it. No unresolved architecture or business decisions were delegated.

Acceptance covers distinct namespaces, 24h retention, reuse without provider calls, owner isolation, evidence/window changes, consent checks on hits and generation, rejected output, eviction/expiry, retention of stale/withdrawn entries and Redis failure.

## Executed verification

All commands ran from the Backend checkout. The disposable operator-supplied fixture was loopback-only. Tests use random synthetic owners and clean up only their explicitly tracked keys; no FLUSHDB or broad key enumeration. The HTTP fixture exercises controller → real Statistics calculations → deterministic fake provider → real Redis. Sleep read contracts and consent are in-memory fixtures; MongoDB persistence and the JWT filter chain are outside this fixture.

| Command or unique literal from executed command | Observed result |
| --- | --- |
| `mvn -o '-Dtest=RedisSleepAiCacheTest' test` | Worker attempt failed before compilation: Boot 4.1.1 Redis starter missing from offline cache. |
| `mvn '-Dtest=RedisSleepAiCacheTest' test` | Worker attempt failed downloading the starter: Maven Central access denied. |
| `mvn -o '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest,RedisSleepAiCacheTest' test` | Orchestrator confirmed dependency resolution failure before compilation; no Maven test pass claimed. |
| `print('P6-CACHE direct Java 21 compilation passed (cached Redis 4.0.1 compatibility check only).')` | Direct compilation of all main/test sources passed using copied cached dependencies; existing deprecation/unchecked warnings. |
| `& 'C:/Program Files/Java/jdk-21/bin/javac.exe' '@target/cache-main-javac.args'` and `& 'C:/Program Files/Java/jdk-21/bin/javac.exe' '@target/cache-test-javac.args'` | Both passed after service/fixture correction. Final test-only compilation also passed after adding two integration cases. |
| `target/CacheVerification.java SleepStoryServiceTest SleepStoryControllerTest RedisSleepAiCacheTest SleepAiEvidenceIntegrationTest SleepAiCacheIntegrationTest` | Direct JUnit launcher: initial 29/33 passed, four new integration failures traced to omitted RANGE-history fixture stub. After fixing that fixture, 33/33 passed, zero skipped. |
| `target/CacheVerification.java SleepAiCacheIntegrationTest` | Final direct JUnit run with `-Dsleep.cache.redis.port=62915`: six real Redis integration cases passed, zero failures/skips, including the additional unavailable-Redis and Explanation consent tests. |
| `git -c safe.directory=D:/prj/vita-loom/vitaloom-backend diff --check` | Passed with no whitespace errors. |
| `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dtest=SleepRecentReportMongoIntegrationTest#storyRequiresPersistedAccountConsentAndCurrentReadyNonA01Evidence' test` with `REDIS_HOST=127.0.0.1`, `REDIS_PORT=62915` | Passed with the declared Spring Boot 4.1.1 graph: 1 test, 0 failures/errors/skips. Docker access was required for the Mongo Testcontainers fixture. |
| Same offline Maven repository and Redis environment: `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dsleep.cache.redis.port=62915' '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest,RedisSleepAiCacheTest,SleepAiCacheIntegrationTest' test` | Passed: 30 tests, 0 failures/errors/skips. All 6 Redis integration tests used the disposable loopback fixture. |
| Same offline Maven repository and Redis environment: `mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' verify` | Passed: 161 tests, 0 failures/errors, 6 skipped; JAR packaging and Spring Boot repackage passed. Docker access was enabled for MongoDB Testcontainers. |

The earlier direct checks used cached Spring Data Redis 4.0.1 and Lettuce 6.8.1, so they were only compatibility checks. The resumed Maven runs above resolve and verify the declared Spring Boot 4.1.1 dependency graph and Redis auto-configuration. Generated compilation arguments, copied libraries and the JUnit launcher are ignored build outputs under `target/`, excluded from delivery.

Tests cover typed serialization, 24h non-overwriting writes, corrupt/read/write failures, invalid cached content, current fallback, no provider on read outage, no writes after stale/withdrawn generation, full-catalog regression and HTTP validation. Redis integration verifies both cache types reuse without regeneration, distinct keys, real TTL, changed evidence leaving old entries present, consent OFF retaining entries, accelerated expiry, explicit eviction, midnight during a hit, stale Explanation hits, consent revision changes during hits, and unavailable loopback Redis.

## Handoff and remaining gates

The original full verify failure was environmental: Spring test contexts defaulted to `localhost:6379` while the supplied disposable Redis fixture was published at `127.0.0.1:62915`. A cache write failure correctly produced the covered deterministic fallback. With `REDIS_HOST` and `REDIS_PORT` set to the fixture, the same persisted-consent test returned STORY and passed; fallback behavior was not changed. Docker named-pipe access also had to be enabled for Mongo Testcontainers.

Confidence is high for this backend unit: the declared dependency graph, focused cache tests, real Redis integration, Mongo-backed target test and full Maven lifecycle passed, and an independent reviewer approved exact commit `a57b094b210f209359f68e8fb72f3c4144c0af5e` with no findings. That commit was fast-forwarded to local `main`. No device check was required for this backend-only unit. No paid-provider call, production data change, push or deployment occurred.

The first offline dependency-resolution and compatibility-test results above are retained as history. The final verification used:

```powershell
mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dtest=SleepRecentReportMongoIntegrationTest#storyRequiresPersistedAccountConsentAndCurrentReadyNonA01Evidence' test
mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' '-Dsleep.cache.redis.port=62915' '-Dtest=SleepStoryServiceTest,SleepStoryControllerTest,RedisSleepAiCacheTest,SleepAiCacheIntegrationTest' test
mvn -o '-Dmaven.repo.local=D:/prj/vita-loom/temp/sleep-p6-maven/repository' verify
```

P6-CACHE has been reviewed and integrated locally. Before a later queue continuation starts P6-COOLDOWN, reconcile the intentionally preserved runner checkpoint with the recorded completion and verify the queue continuation. Do not infer completion of later P6 units from this result.

P6-EVIDENCE is the accepted prerequisite. P6-CACHE checks, independent review and local integration passed. P6-COOLDOWN, P6-DEADLINE, P6-SINGLEFLIGHT and P6-FE-REFRESH remain NOT STARTED here. P6-REAL-PROVIDER remains BLOCKED on its separate prerequisites and privacy/security/budget gates. This delivery does not claim P6 production readiness.
