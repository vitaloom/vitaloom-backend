# Package structure

This document defines the target Java package layout for VitaLoom Backend. It applies to new code and code migrated from the demo repository. It does not authorize unrelated refactoring in the demo workspace.

The application remains a **single Spring Boot application and a single Maven module**. Organize code by **feature first, layer second**. Each feature is a direct child package of the application base package. Do not introduce an extra parent grouping package above features.

Create only the packages a feature actually needs; do not add empty placeholder packages.

## 1. Base package and target layout

Use `com.vitaloom.backend` as the application base package.

```text
com.vitaloom.backend
├── heartrate
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   ├── exception
│   ├── model
│   ├── repository
│   └── service
├── steps
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   ├── exception
│   ├── model
│   ├── repository
│   └── service
├── sleep
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   ├── exception
│   ├── model
│   ├── repository
│   └── service
├── water
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   ├── exception
│   ├── model
│   ├── repository
│   └── service
├── statistics
│   ├── controller
│   │   └── exceptionhandler
│   ├── dto
│   │   └── response
│   ├── exception
│   └── service
├── healthcheck
│   ├── controller
│   └── dto
│       └── response
├── shared
│   └── web
│       └── exceptionhandler
├── config
└── VitaLoomBackendApplication
```

This is a target layout, not a requirement to create every directory immediately. A package is created when the feature needs the responsibility represented by that package.

## 2. Feature ownership

Every feature package above is a direct child of `com.vitaloom.backend`. There is no additional `health`, `system` or other domain-grouping package above them.

### Metric features — raw measurement ownership

The `heartrate`, `steps`, `sleep` and `water` features own their respective raw measurement lifecycles:

- Input/ingestion endpoints and their request/response DTOs.
- Metric-specific raw-data validation and business invariants.
- Their own MongoDB collections, persistence models, indexes and write behavior.
- Raw-record identity, duplicate/conflict handling and persistence guarantees where applicable.
- Service-level raw-data query capabilities needed by Statistics.

Metric features do not own derived aggregate calculations or aggregate read models. They must not depend on Statistics.

### `heartrate` — Heart Rate measurements

Owns:

- Heart Rate batch ingestion and its request/response DTOs.
- Heart Rate measurement validation and persistence.
- The `heart_rate_measurements` MongoDB collection and its indexes.
- Heart Rate measurement identity and duplicate/conflicting-ID handling.
- Raw Heart Rate measurement queries needed by Statistics.

A Heart Rate measurement is persisted using its own metric-specific model. Do not place Heart Rate data in a generic cross-metric event document or collection.

### `steps` — Steps measurements

Owns:

- Steps batch ingestion and its request/response DTOs.
- Step increment validation and persistence.
- The `step_measurements` MongoDB collection and its indexes.
- Step measurement identity and duplicate/conflicting-ID handling.
- Raw Steps measurement queries needed by Statistics.

Steps uses its own metric-specific model and persistence collection. Do not place Steps data in the Heart Rate collection or a generic cross-metric event collection.

### Metric separation and shared conventions

Heart Rate and Steps have different measurement semantics and validation. Keep their persistence models, repositories and raw-data behavior separate.

Both metrics follow the same cross-cutting principles where applicable: authenticated account ownership, source/device identity, stable source event IDs, event time (`occurredAt`), server receipt time (`receivedAt`) and database-enforced protection against duplicate writes.

This commonality does **not** require a generic `HealthEvent` model, shared collection, common repository or shared service. Do not introduce shared abstractions solely to remove a small amount of duplication. Extract a genuinely reusable mechanism only when implementation demonstrates a concrete need.

### `sleep` — manually entered sleep

Owns sleep input and its raw-record persistence rules, including the current one-record-per-local-day behavior. It exposes raw sleep data queries needed by Statistics. It is independent of Heart Rate and Steps ingestion.

### `water` — manually entered water intake

Owns water-intake input, raw-record validation and persistence. It exposes raw water-intake queries needed by Statistics. It is independent of Sleep and producer metric ingestion.

### `statistics` — aggregate read model and cross-metric composition

Statistics owns the derived view of health data. Its responsibilities include:

- Daily statistics endpoints and response DTOs.
- Metric-specific aggregate calculations, such as Heart Rate daily average/latest observation, Steps totals, Sleep duration and Water intake totals.
- Time-bucketed or other aggregate series when an API needs derived values rather than raw measurements.
- Cross-metric composition for dashboard and overview responses.
- The meaning and consistency of all derived aggregate/read-model values.

Statistics obtains raw measurements through the owning metric features' service-level query capabilities. It must not reach into another feature's repository, Mongo document or raw Mongo query. Metric features do not calculate or persist aggregate read models on Statistics' behalf.

**Current MVP strategy — calculate on read:** Statistics derives requested aggregates from raw metric records when the relevant read request is processed. Raw metric records remain the source of truth. Do not create aggregate collections, Statistics repositories/models, scheduled jobs, background workers or refresh infrastructure merely in anticipation of future scale.

**Future direction — precomputed aggregates:** The architecture allows Statistics to persist precomputed/materialized aggregate read models when a concrete performance or product requirement justifies them. If that phase is approved, the aggregate schema, persistence, update/recalculation lifecycle and consistency policy belong to Statistics. The metric features continue to own the raw source records. The detailed precomputation mechanism is intentionally not decided by this document.

The package tree above therefore shows only the current Statistics packages. Add Statistics `model` or `repository` packages only when an approved precomputed-read-model implementation actually needs them.

### `healthcheck` — application availability endpoint

Owns the operational `GET /api/health` endpoint. This is an application health check, not a health measurement feature.

Keep its controller and response DTO inside `healthcheck`; do not put a standalone controller in the application root and do not create a broader `system` namespace just for this endpoint.

## 3. Layer responsibilities

| Package | Responsibility | Must not own |
|---|---|---|
| `controller` | HTTP routing, request binding, status/headers and response adaptation | Business rules, Mongo access |
| `dto.request` | Typed API request shapes | Persistence documents |
| `dto.response` | Typed API response shapes | Database query or business orchestration |
| `service` | Use cases, business invariants, orchestration and feature query capabilities | Raw Mongo query construction |
| `repository` | MongoDB reads/writes, query construction, indexes and atomic persistence operations | HTTP behavior or business workflow orchestration |
| `model` | Feature-owned persisted documents and domain/value types | HTTP DTOs, controllers or services |
| `exception` | Feature-owned application failures | HTTP-specific response formatting |
| `controller.exceptionhandler` | Translation of feature-specific failures into HTTP responses | Business decisions |
| `shared.web` | Generic HTTP response types and framework-level error handling that has no feature dependencies | Feature-specific business rules or exceptions |

Create `mapper`, `contract`, `infra`, `utils` or additional subpackages only when a concrete responsibility requires them. Do not add a package simply because it exists in another repository's template.

The `healthcheck` feature may only need `controller` and `dto.response`. Do not create artificial service or repository layers for a controller that has no business or persistence behavior.

## 4. Dependency direction

Apply these boundaries to new code and code being refactored:

- A controller depends on its feature's service and DTOs. It must not call repositories or manipulate persistence models directly.
- A service may depend on its feature's repository, model and DTOs. Keep Mongo-specific query types inside the repository.
- A repository may depend on its feature's persistence model and Mongo support, but not on controllers or services.
- A model must not depend on HTTP DTOs, controllers, services or repositories.
- `shared` contains only cross-cutting types that do not depend on feature packages. Feature-specific concepts must not be moved into `shared` to avoid a dependency.
- `config` owns Spring configuration and wiring, not application business logic.
- Avoid circular dependencies between feature packages.

### Statistics-to-metric dependency

The intended dependency is one-way:

```text
statistics
    ├── queries raw Heart Rate data through heartrate service
    ├── queries raw Steps data through steps service
    ├── queries raw Sleep data through sleep service
    └── queries raw Water data through water service

heartrate / steps / sleep / water
    └── do not depend on statistics
```

Metric services expose only the raw-data query capabilities Statistics needs. A direct dependency on those service capabilities is sufficient; do not introduce an interface/contract layer solely for this call. Add a stronger public contract only if a real reuse, substitution or extraction requirement appears.

Statistics must not bypass the service boundary by injecting another feature's repository or querying its Mongo collection directly. Statistics owns aggregate calculation and any future aggregate persistence.

## 5. HTTP exception handling

- Put generic API error response types and truly feature-independent framework error handling in `shared.web` / `shared.web.exceptionhandler`.
- Keep feature-specific application exceptions in the owning feature's `exception` package.
- Keep feature-specific HTTP translation in that feature's `controller.exceptionhandler` package.
- Generic shared handlers must not import feature exceptions. This prevents `shared` from depending on feature packages.

## 6. Tests

Mirror production package ownership under `src/test/java`:

```text
src/test/java/com/vitaloom/backend/
├── heartrate/
├── steps/
├── sleep/
├── water/
├── statistics/
├── healthcheck/
└── ...
```

- Unit tests for a feature should normally live alongside that feature's package path.
- Repository/integration tests belong under the owning feature.
- Tests for metric-specific and cross-metric aggregate calculations belong under `statistics`; use metric service boundaries or focused test doubles as appropriate.
- Add architecture tests only when the package boundaries have stabilized and a rule is valuable to enforce automatically. Documentation remains the policy source for rules that are not automated.

## 7. Adoption and non-goals

- Apply this structure to new code and code moved into `vitaloom-backend`.
- Do not reorganize unrelated code in `vitaloom-demo` solely to make it match this target.
- Heart Rate and Steps use separate metric-owned persistence models and collections. Do not create a generic cross-metric event collection as a shortcut.
- During migration, preserve endpoint behavior, stored identifiers, Mongo collection/document compatibility and event idempotency unless a separate change explicitly approves a behavior or data migration.
- Keep this a modular monolith. Do not create separate Maven modules, microservices, message brokers or shared libraries for the current metrics.
- Do not pre-create packages for future goals, reminders, journals, insights or other roadmap capabilities. Add a feature when its backend responsibility and ownership are real.
- Update this document when the approved feature ownership or dependency direction changes.
- Record significant accepted architecture decisions in [Architecture Decisions](./decisions/README.md), and link the relevant ADR here when it defines a package ownership or dependency rule.
