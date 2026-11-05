# VitaLoom Backend

Backend repository for VitaLoom. The intended application stack is Java 21, Spring Boot and MongoDB. The backend application source is being moved from the local demo workspace; until that migration is completed, the current implementation reference remains [`vitaloom-demo/demo`](https://github.com/vitaloom/vitaloom-demo/tree/main/demo).

## Repository guidance

- [`AGENTS.md`](./AGENTS.md) — entry point and non-negotiable instructions for contributors and coding agents.
- [Working Agreement](./docs/agent/working-agreement.md) — how to investigate, implement, retry and verify work.
- [Definition of Done](./docs/agent/definition-of-done.md) — completion criteria and verification expectations.
- [Implementation Conventions](./docs/architecture/implementation-conventions.md) — coding, HTTP, service, persistence and testing conventions.

Package structure is intentionally handled as a separate, upcoming step. Until that guide is added, avoid broad package restructuring. The current API behavior baseline is documented in [`vitaloom-demo/API_CONTRACT.md`](https://github.com/vitaloom/vitaloom-demo/blob/main/API_CONTRACT.md) and will become the contract source in this repository in a later step.

## Scope

This repository owns backend behavior, persistence and the API contract consumed by the separate VitaLoom Mobile and Data Producer repositories. Keep cross-repository compatibility in mind when changing endpoint behavior, payloads, identifiers, timestamps or aggregation rules.

The current demo is not a production multi-user health-data service. Authentication and user-level data isolation have not been implemented.
