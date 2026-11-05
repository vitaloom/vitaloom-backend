# Agent instructions

VitaLoom Backend is the owner of backend application behavior, persistence and the API contract consumed by the separate Mobile and Data Producer repositories.

The intended stack is Java 21, Spring Boot and MongoDB. The backend source has not yet been moved into this repository; the current implementation reference is [vitaloom-demo/demo](https://github.com/vitaloom/vitaloom-demo/tree/main/demo). Do not assume that this repository already contains a Maven project, build wrapper or executable test suite.

## Start here

Read the documents relevant to the task:

- `docs/architecture/implementation-conventions.md` — coding, HTTP, service, persistence and testing conventions.
- `docs/agent/working-agreement.md` — task workflow, implementation loop and failure/retry discipline.
- `docs/agent/definition-of-done.md` — completion and verification criteria.

Until the dedicated package-structure document is established, preserve the existing package layout of the code being changed. Do not perform broad package moves as part of an unrelated task.

The current API behavior baseline is documented in [vitaloom-demo/API_CONTRACT.md](https://github.com/vitaloom/vitaloom-demo/blob/main/API_CONTRACT.md). It remains the reference until the API contract is formally moved into this repository.

## Non-negotiable

- Inspect the relevant implementation, tests and API contract before changing behavior.
- Preserve the agreed API and persistence contracts unless the task explicitly approves a change. Mobile and Data Producer are separate consumers; account for their compatibility.
- Keep the application a maintainable modular backend. Do not introduce microservices, brokers, caches, extra Maven modules or infrastructure without a concrete requirement and approval.
- Keep raw health events/records as the source of truth. Backend validation, persistence and aggregation remain authoritative; clients and the producer must not submit authoritative daily totals.
- Preserve event identity and idempotency. A retry of the same logical event must not create a second measurement or double-count a metric; conflicting reuse of an ID must remain distinguishable from an identical retry.
- Daily statistics currently use `Asia/Ho_Chi_Minh`. Do not change the timezone, day boundaries or timestamp interpretation without an explicit contract decision.
- Do not invent health-domain rules, clinical interpretations or medical claims. The application tracks and displays data; it must not silently turn implementation assumptions into health advice.
- Authentication and per-user data isolation are not part of the current local-demo contract. Do not imply that the current API is safe for real multi-user health data or add an authentication design without an approved scope.
- Do not add dependencies, top-level package roots, architectural abstractions or persistence migrations merely to match another repository's conventions.
- Do not weaken, disable, suppress or bypass tests and checks to obtain a passing result.

## Code comments

- Prefer names and structure that explain the code.
- Add comments only for non-obvious reasons such as an invariant, ordering requirement, compatibility constraint or deliberate trade-off.
- Do not add comments that restate the implementation or boilerplate Javadoc.

## Before declaring completion

Follow the working agreement and the applicable Definition of Done. Report the checks actually run and their observed results, checks not run or failed, manual steps still required, and known limitations. A task checkbox or a successful edit is not verification.
