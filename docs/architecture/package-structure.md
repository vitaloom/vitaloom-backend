# Package structure

VitaLoom Backend is one Spring Boot application and Maven module rooted at `com.vitaloom.backend`. Organize code feature first, then by the layers a feature actually needs. This page describes the current package layout and owns package placement, feature ownership and dependency direction. Coding and representation rules live in [implementation conventions](implementation-conventions.md).

## Current layout

```text
com.vitaloom.backend
├── auth              controller, dto, exception, mapper, model, repository, service/impl
├── heartrate         contract/dto, controller, dto, exception, mapper, model, repository, service/impl
├── steps             raw Steps Journal and AHM Daily Step Goal subfeature
├── water             controller, dto, exception, model, repository, service/impl
├── statistics        controller/exceptionhandler, dto/response, exception, service/impl
├── healthcheck       controller, dto/response
├── shared/persistence
├── config
└── VitaLoomBackendApplication
```

`service` holds capability interfaces and `service.impl` holds their current implementations. `mapper` currently appears in Auth and Heart Rate; add one elsewhere only for meaningful transformations. `shared.persistence.BaseDocument` supplies the String Mongo ID; `AuditedDocument` adds auditing fields and is currently used by `auth.model.User`. Do not create empty packages or move code solely to make the tree look uniform.

## Example feature layout

This is an illustrative layout for a feature that needs each shown responsibility; it is not a second description of the current runtime. Create only packages needed by real responsibilities.

```text
<feature>
├── controller
│   └── exceptionhandler       feature-specific HTTP advice, when needed
├── dto
│   ├── request                HTTP request shapes
│   └── response               HTTP response shapes
├── service                    business capability interfaces
│   └── impl                   business service implementations
├── model                      feature-owned persistence/domain types
├── repository                 feature-owned persistence access
├── mapper                     meaningful feature-local transformations
├── exception                  feature-owned application failures
└── contract                   optional provider boundary (when needed)
    ├── <ProviderQuery>        provider capability interface
    └── dto                    provider input/result types
```

Provider read contracts use `<provider>.contract` for the interface and `.contract.dto` for exchanged values. Keep HTTP DTOs in `dto.request` / `dto.response`, persistence documents in `model`, feature failures in `exception`, and feature HTTP translation in `controller.exceptionhandler`.

## Feature ownership and status

| Feature | Status | Owns | Current Statistics dependency |
| --- | --- | --- | --- |
| `auth` | Implemented | Registration, login/refresh, account model and authentication behavior | None |
| `heartrate` | Implemented | Raw Heart Rate ingestion, validation, persistence and raw-data queries | Live: Statistics uses `HeartRateReadQuery` |
| `steps` | Implemented | Raw step ingestion, validation, persistence and raw-data queries | Live: Statistics uses `StepsReadQuery` |
| `water` | Implemented | Water log input, validation, persistence and Water summaries | None; not yet composed by Statistics |
| `sleep` | Implemented for F1 | Sleep Journal sessions and validation | No Statistics dependency |
| `statistics` | Partially implemented | Aggregate calculations and read responses | Uses Heart Rate and Steps provider read contracts |
| `healthcheck` | Implemented | Application availability endpoint | None |

The cross-metric daily dashboard and Statistics composition for Sleep and Water are planned. Water Tracking itself is implemented and documented under [Water](../features/water/README.md) and its [API contract](../features/water/api-contract.md). A documented endpoint or future package does not imply runtime implementation; check [API contract index](../api/api-contract.md) and source before relying on it.

Statistics reaches Heart Rate and raw Steps through `HeartRateReadQuery` and `StepsReadQuery`, with exchanged sample types in each provider's `contract.dto`. It reads AHM-owned Step Goal history through the separate `steps.goal.contract.StepGoalReadQuery`; Statistics does not inject goal or metric repositories or query collections directly. Statistics owns derived values; Steps Journal owns raw records and the Steps goal subfeature owns goal snapshots. See [ADR-001](decisions/ADR-001-statistics-owns-aggregate-read-models.md).

## Dependency direction

| Component | May depend on | Must not depend on |
| --- | --- | --- |
| Controller | Its feature's service and API DTOs | Repositories or persistence models directly |
| Service | Its feature's repository, model, DTOs and shared technical types | Another feature's repositories, Mongo documents or raw queries |
| Statistics | Heart Rate and Steps provider read contracts, Step Goal read contract, and its own calculations/response types | Metric/goal write service interfaces, repositories, Mongo documents or raw queries |
| Repository | Its feature's persistence models and Mongo support | Controllers or services |
| Mapper | Feature-local values it transforms | Controllers, repositories or business decisions |
| Model | Feature-owned values and shared persistence base types | HTTP DTOs, controllers, services or repositories |
| `shared` | Cross-cutting types with no feature knowledge | Feature packages |
| `config` | Spring configuration and wiring | Feature business logic |

Keep dependencies one-way and avoid feature cycles. Raw metric records remain the source of truth; Statistics calculates on read in the current MVP. Do not add aggregate persistence or refresh workers without an approved requirement.

No ArchUnit architecture test currently enforces these package rules. Treat this document as policy, and do not claim automated enforcement unless a build check is added and verified.
