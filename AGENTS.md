# Agent instructions

VitaLoom Backend owns backend application behavior, persistence and the API contract consumed by the separate Mobile and Data Producer repositories.

## Current repository baseline

This repository already contains the Java 21 / Spring Boot application bootstrap, Maven Wrapper, MongoDB local-development infrastructure and baseline Spring configuration. Business features are migrated into this repository incrementally.

\`docs/api/api-contract.md\` is the canonical API-contract index and shared-conventions entry point. Detailed contracts are split by area under \`docs/api/\` (including one file per metric in \`docs/api/metrics/\`). A documented endpoint describes expected behavior; it does **not** mean it is implemented. Read the relevant contract and inspect current controllers, services and tests before changing or relying on an endpoint.

The former demo implementation remains available in [\`vitaloom-demo/demo\`](https://github.com/vitaloom/vitaloom-demo/tree/main/demo) as a migration and behavior reference. The backend repository and its API contract are the source of truth for new backend work.

## Start here

Read the documents relevant to the task:

- [\`README.md\`](./README.md) — prerequisites, local environment, infrastructure and run/test commands.
- [\`docs/api/api-contract.md\`](./docs/api/api-contract.md) — API-contract index and shared conventions.
- [\`docs/api/\`](./docs/api/) — detailed authentication, dashboard and metric-specific API contracts; read the file relevant to your task.
- [\`docs/architecture/package-structure.md\`](./docs/architecture/package-structure.md) — feature ownership, package layout and dependency direction.
- [\`docs/architecture/decisions/README.md\`](./docs/architecture/decisions/README.md) — accepted architecture decisions and their rationale; read relevant ADRs before changing an established design.
- [\`docs/architecture/implementation-conventions.md\`](./docs/architecture/implementation-conventions.md) — coding, HTTP, service, persistence and testing conventions.
- [\`docs/agent/working-agreement.md\`](./docs/agent/working-agreement.md) — task workflow, implementation loop and failure/retry discipline.
- [\`docs/agent/definition-of-done.md\`](./docs/agent/definition-of-done.md) — completion and verification criteria.

For new code and code migrated into this repository, follow the target layout in \`docs/architecture/package-structure.md\`. Create packages only when a concrete implementation needs them; do not preserve empty placeholder packages or perform broad unrelated package moves.

## Non-negotiable

- Inspect the relevant implementation, tests, configuration and API/persistence contract before changing behavior.
- Preserve the agreed API and persistence contracts unless the task explicitly approves a change. Mobile and Data Producer are separate consumers; account for their compatibility.
- Keep the application a maintainable modular backend. Do not introduce microservices, brokers, caches, extra Maven modules or infrastructure without a concrete requirement and approval.
- Metric features own their raw health records, ingestion validation, identity, persistence and raw-data query capabilities. Statistics owns the calculations and derived aggregate/read-model data built from those records, including metric-specific aggregates and cross-metric composition. Follow [ADR-001](./docs/architecture/decisions/ADR-001-statistics-owns-aggregate-read-models.md); do not move aggregate ownership back into metric features.
- Keep raw health records as the source of truth. During the current MVP phase, Statistics calculates aggregates on read; do not add precomputed aggregate persistence, background aggregation workers or refresh infrastructure without a separately approved implementation scope.
- Preserve event identity and idempotency. A retry of the same logical event must not create a second measurement or double-count a metric; conflicting reuse of an ID must remain distinguishable from an identical retry.
- Daily statistics currently use \`Asia/Ho_Chi_Minh\`. Do not change the timezone, day boundaries or timestamp interpretation without an explicit contract decision.
- Do not invent health-domain rules, clinical interpretations or medical claims. The application tracks and displays data; it must not silently turn implementation assumptions into health advice.
- Basic registration/login/refresh and authenticated-account endpoints exist. Heart Rate batch ingestion (\`POST /api/heart-rate/batch\`) and all Statistics Heart Rate routes require Bearer authentication and scope Heart Rate records to the JWT subject. The Steps API contract also requires Bearer authentication and JWT-subject account scoping for \`POST /api/steps/batch\` and \`/api/statistics/steps/**\`, but Steps endpoints are not implemented yet. Sleep/Water APIs and the cross-metric daily dashboard do not yet enforce equivalent per-user access and isolation; do not imply those paths are safe for real multi-user private health data.
- Do not add dependencies, top-level package roots, architectural abstractions or persistence migrations merely to match another repository's conventions.
- Do not weaken, disable, suppress or bypass tests and checks to obtain a passing result.

## Code comments

- Prefer names and structure that explain the code.
- Add comments only for non-obvious reasons such as an invariant, ordering requirement, compatibility constraint or deliberate trade-off.
- Do not add comments that restate the implementation or boilerplate Javadoc.

## Before declaring completion

Follow the working agreement and the applicable Definition of Done. Report the checks actually run and their observed results, checks not run or failed, manual steps still required, and known limitations. A task checkbox or a successful edit is not verification.
