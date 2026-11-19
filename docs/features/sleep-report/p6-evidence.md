# P6-EVIDENCE delivery · 2026-11-16

Scope: canonical evidence identities and Validate Before Publish for the test-only deterministic Story and Explanation provider. The runner owns Git delivery and independent review; changes are left unstaged. No live Gemini request, production data or infrastructure is involved.

## Implementation and compatibility

Story now reads `fullInsights` for the authenticated subject and explicit selected A04 zone, independently of Recent ranking/highlight flags/cap. Only published READY unlocked A01–A05/B01–B04 enter the provider pool. An unrelated unverified evaluator is omitted without removing another verified READY evaluator. A01 alone remains ineligible. No zone is inferred from available zones, the account or the device.

A versioned SHA-256 identity covers the exact immutable normalized provider payload: current report period, explicit zone, sorted candidate codes, each candidate's policy, unchanged public Detail version, own period, full sample and evidence maps. Trend evidence retains both completed windows and all baseline/recent values. Map keys and numeric representations are canonical; evidence lists preserve their defined order. Deep snapshots prevent a provider or later source-map mutation from changing the original comparison. Raw records, source references and account identifiers are excluded from provider input. Future cache keys must also namespace the identity by authenticated owner and prompt/policy version.

After one provider invocation, Story rereads the full pool with the same owner/zone. Any identity change rejects generated text and uses the currently eligible deterministic fallback, with no regeneration. Lost eligibility returns no content. Explanation rereads its current READY Detail and compares its complete payload; changed evidence returns the existing `STALE` response, or `INELIGIBLE` if READY was lost. Both paths reread the persisted consent revision, so revoke/re-enable suppresses all content. Public `evidenceVersion`, observation/Explanation `evidenceFingerprint`, and version hints retain their existing Detail compatibility.

The fake templates support all nine codes with complete-sentence validation, at most two Story observations, a non-A01 requirement and the existing Vietnamese word budgets. Story GET accepts optional `timeZone`; Explanation POST accepts optional body `timeZone` only for A04. Existing no-zone requests remain valid. Mobile adapters recognize all published codes and carry the already-selected authenticated session zone. Screen lifecycle and existing UI capability gates are outside this unit.

## Work and ownership

- Context discovery: one context-reader; CodeGraph first against the current worktrees.
- Backend identity, publication checks, HTTP contract and integration: orchestrator.
- Backend focused test packet: luna_worker `backend_tests`, integrated and extended by the orchestrator.
- Mobile adapter/types/tests/owner docs: luna_worker `mobile_adapter`, reviewed by the orchestrator.
- No changes to shared task records, phase ledger, queue or runner artifacts.

## Verification

Final execution results and environment limits are recorded below. All fixture data is synthetic and isolated.

- `SleepStoryServiceTest`: full uncapped pool, non-selected evidence changes despite an unchanged source version, canonical map/numeric stability, fabricated output, consent and revoke/re-enable for both paths, stale Explanation.
- `SleepAiEvidenceIntegrationTest`: real Statistics calculations over mocked Sleep-owned read contracts, full nine-code pool, both trend windows, Goal evidence, stable repeated reads, each template/word budget, day rollover, changed/missing feeling, Goal changes, explicit A04 selection and HTTP controller → Statistics → fake provider → publish path. This representative integration does not use MongoDB or the JWT security filter chain.
- `SleepStoryControllerTest`: authenticated principal forwarding, nine-code compatibility, no-store, optional zone transport and invalid/unknown/repeated parameter rejection.
- Mobile `sleep-*.test.cjs`: all Sleep tests, including every new Story/Explanation code and selected zone transport.

The Windows Maven wrapper failed before Maven launch (`Cannot index into a null array`). Installed Maven's default offline cache lacked the parent POM. Selecting the existing cache resolved dependencies, but normal Maven compilation failed while Java closed external-cache JAR files (`AccessDeniedException` in `ZipFileSystem`/`toRealPath`); Java 21 had the same issue. Copying the 119 test-classpath dependency JARs (about 70 MB) into ignored `target/evidence-dependencies` allowed direct Java 21 compilation of all current main/test sources. Maven Surefire then ran those freshly compiled classes. No build/POM/dependency configuration was changed.

Executed checks (commands below are exact commands or unique literal substrings from the executed shell commands):

| Check | Command / literal | Observed result |
| --- | --- | --- |
| Compile | `print('P6-EVIDENCE Java 21 compilation passed for all main and test sources.')` | Direct Java 21 compilation of all main/test sources exited 0; existing deprecation and unchecked-test warnings only. Uses `javac.exe @target/main-evidence-javac.args` and `@target/test-evidence-javac.args`, generated with the copied classpath and Lombok processor. |
| Final service rerun | `'-Dtest=SleepStoryServiceTest' surefire:test` | 13 tests passed after `& 'C:/Program Files/Java/jdk-21/bin/javac.exe' '@target/test-evidence-javac.args'` exited 0. |
| Backend Sleep regression | `'-Dtest=Sleep*Test,!SleepJournalMongoIntegrationTest,!SleepRecentReportMongoIntegrationTest,!SleepRecentReportErrorTest' surefire:test` | 54 tests, zero failures/errors/skips; includes five real-Statistics integration cases and four HTTP adapter cases. After a test-fixture consistency cleanup, the 13 service tests were recompiled and rerun successfully with the same production code. |
| Mobile Sleep regression | `node --test $sleepTests` | 91 passed, zero failures/skips. |
| Mobile focused adapter | `node --test tests/sleep-insights-http.test.cjs` | 23 passed. |
| Mobile typecheck | `npx tsc --noEmit` | Exit 0. |
| Mobile lint | `npx expo lint` | Exit 0. |
| Diff hygiene | `git -c safe.directory=D:/prj/vita-loom/vitaloom-backend -C vitaloom-backend diff --check` and `git -c safe.directory=D:/prj/vita-loom/vita-loom-mobile -C vita-loom-mobile diff --check` | Exit 0, no whitespace errors. |

Failed environment attempts (no test pass is inferred from these):

- `.\mvnw.cmd -o '-Dtest=SleepStoryServiceTest' test`: wrapper launcher null-array failure before Maven. A worker's `.\mvnw.cmd -o -q -Dtest=SleepStoryServiceTest test` failed the same way.
- `mvn -o '-Dtest=SleepStoryServiceTest' test` and worker `mvn -o -q -Dtest=SleepStoryServiceTest test`: missing parent POM in default offline cache.
- `'-Dtest=SleepStoryServiceTest' test`, the `-e` diagnostic run, the worker `'-Dtest=SleepStoryServiceTest,SleepAiEvidenceIntegrationTest' test` variant, and `Java 21's '-Dtest=SleepStoryServiceTest' clean test`: external-cache JAR path access failures during compiler shutdown.
- The initial direct test compilation found two wildcard-map assertion type errors; fixed before the passing compilation.
- Initial `'-Dtest=SleepStoryServiceTest' surefire:test`: no compiled tests after the preceding compiler failure; later runs used freshly compiled sources and passed.
- `docker info --format '{{.ServerVersion}}'`: Docker configuration/daemon access denied. Mongo-backed tests were not run.

Confidence is medium: current code is compiled and the focused, regression and representative HTTP/Statistics paths pass, while full infrastructure/native verification remains unavailable.

## Deferred gates

P6-CACHE, P6-COOLDOWN, P6-DEADLINE, P6-SINGLEFLIGHT and P6-FE-REFRESH are NOT STARTED in this delivery. P6-REAL-PROVIDER remains BLOCKED on its separate privacy/security/budget and prerequisite gates; the existing production disablement remains. This delivery does not claim P6 production readiness.

Docker daemon access is denied in this session. Mongo-backed tests, full Maven lifecycle verification and device E2E have not passed here. The independent runner review must assess these remaining environment checks against the exact delivery commits. Next action: runner stages/commits this scoped diff and runs its independent review gates.
