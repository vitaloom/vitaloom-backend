# Package structure

VitaLoom Backend is one Spring Boot application and Maven module rooted at `com.vitaloom.backend`. Organize code feature first, then by the layers a feature actually needs. This page describes the current package layout and owns package placement, feature ownership and dependency direction. Coding and representation rules live in [implementation conventions](implementation-conventions.md).

## Current layout

```text
com.vitaloom.backend
├── auth              controller, dto, exception, mapper, model, repository, service/impl
├── heartrate         controller, dto, exception, mapper, model, repository, service/impl
├── steps             controller, dto, exception, model, repository, service/impl
├── water             controller, dto, exception, model, repository, service/impl
├── statistics        controller/exceptionhandler, dto/response, exception, service/impl
├── healthcheck       controller, dto/response
├── shared/persistence
├── config
└── VitaLoomBackendApplication
```

`service` holds capability interfaces and `service.impl` holds their current implementations. `mapper` currently appears in Auth and Heart Rate; add one elsewhere only for meaningful transformations. `shared.persistence.BaseDocument` supplies the String Mongo ID; `AuditedDocument` adds auditing fields and is currently used by `auth.model.User`. Do not create empty packages or move code solely to make the tree look uniform.

For new or refactored code, HTTP request and response types belong in `dto.request` and `dto.response`; feature failures belong in `exception`, and feature HTTP translation belongs in `controller.exceptionhandler`. Keep persistence documents in `model` and transformations in `mapper`. The approved provider contracts use `<provider>.contract` for the interface and `.contract.dto` for exchanged values; these are planned packages, not part of the current tree.

## Feature ownership and status

| Feature | Status | Owns | Current Statistics dependency |
| --- | --- | --- | --- |
| `auth` | Implemented | Registration, login/refresh, account model and authentication behavior | None |
| `heartrate` | Implemented | Raw Heart Rate ingestion, validation, persistence and raw-data queries | Live: Statistics calls `HeartRateService` |
| `steps` | Implemented | Raw step ingestion, validation, persistence and raw-data queries | Live: Statistics calls `StepsService` |
| `water` | Implemented | Water log input, validation, persistence and Water summaries | None; not yet composed by Statistics |
| `sleep` | Planned | Future raw sleep input and persistence | Planned only |
| `statistics` | Partially implemented | Aggregate calculations and read responses | Currently uses Heart Rate and Steps service interfaces |
| `healthcheck` | Implemented | Application availability endpoint | None |

The cross-metric daily dashboard and Statistics composition for Sleep and Water are planned. Water Tracking itself is implemented and documented under [Water](../features/water/README.md) and its [API contract](../features/water/api-contract.md). A documented endpoint or future package does not imply runtime implementation; check [API contract index](../api/api-contract.md) and source before relying on it.

Statistics currently reaches Heart Rate and Steps through their in-process service interfaces; it does not inject their repositories or query their collections directly. The approved Phase 2 migration introduces `heartrate.contract.HeartRateReadQuery` and `steps.contract.StepsReadQuery`, with exchanged types in each provider's `contract.dto`. Until the consumers are migrated, the current service-interface calls remain in place; after migration, Statistics must consume these provider contracts instead of metric service interfaces. Do not treat the provider contracts as implemented before that migration. Statistics owns derived values; metric features own raw records. See [ADR-001](decisions/ADR-001-statistics-owns-aggregate-read-models.md).

## Dependency direction

| Component | May depend on | Must not depend on |
| --- | --- | --- |
| Controller | Its feature's service and API DTOs | Repositories or persistence models directly |
| Service | Its feature's repository, model, DTOs and shared technical types; Statistics may use the current Heart Rate and Steps service interfaces | Another feature's repositories, Mongo documents or raw queries |
| Repository | Its feature's persistence models and Mongo support | Controllers or services |
| Mapper | Feature-local values it transforms | Controllers, repositories or business decisions |
| Model | Feature-owned values and shared persistence base types | HTTP DTOs, controllers, services or repositories |
| `shared` | Cross-cutting types with no feature knowledge | Feature packages |
| `config` | Spring configuration and wiring | Feature business logic |

Keep dependencies one-way and avoid feature cycles. Raw metric records remain the source of truth; Statistics calculates on read in the current MVP. Do not add aggregate persistence or refresh workers without an approved requirement. Implement the approved provider contracts as part of the Phase 2 consumer migration; until then, retain the current direct service-interface calls.

No ArchUnit architecture test currently enforces these package rules. Treat this document as policy, and do not claim automated enforcement unless a build check is added and verified.
