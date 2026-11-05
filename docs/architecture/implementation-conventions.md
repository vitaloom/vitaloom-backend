# Backend implementation conventions

These conventions apply to new code and code being refactored in VitaLoom Backend. They are a baseline for this repository, not a mandate to mechanically rewrite the current demo implementation. Inspect relevant callers, stored data, tests and API behavior before changing an existing pattern. Do not expand a task into unrelated cleanup.

Package placement and module boundaries will be defined in a separate package-structure document. Until that document is established, preserve the package layout of the code being changed and do not use this document as a reason for broad package moves.

## 1. Naming

| Kind | Convention | Example |
|---|---|---|
| Java package | Lowercase, no underscores | `health.event` |
| Java class/interface | PascalCase; name the owned responsibility | `DailyStatisticsService` |
| Java field/method | camelCase | `occurredAt`, `getDailyStatistics()` |
| HTTP request DTO | Purpose + `Request` suffix | `WaterIntakeRequest` |
| HTTP response DTO | Purpose + `Response` suffix | `DailyStatisticsResponse` |
| Persistence access | `Repository` suffix | `HealthEventRepository` |
| Exception | Describe the failure condition | `EventIdConflictException` |

Avoid vague names such as `Helper`, `Manager` or `Processor` unless the name is qualified by a clear responsibility.

## 2. Responsibility boundaries

### Controllers

- Controllers are HTTP adapters. They parse the request, invoke an application capability and translate the result into the agreed HTTP response.
- Keep business decisions, aggregation and persistence operations out of controllers.
- Use typed request and response DTOs for known API shapes. Do not expose MongoDB documents or persistence-specific types as API payloads.
- Use `ResponseEntity` when the endpoint needs to express a meaningful status or header; do not add response wrappers or a universal envelope unless the API contract calls for one.
- Keep provider/producer-facing ingestion endpoints consistent with the published API contract.

### Services

- Services own application use cases, business validation, orchestration and decisions that must remain consistent across entry points.
- Keep each service cohesive. Do not create one service class per endpoint by default, and do not extract a wrapper that only forwards identical arguments and results without owning a responsibility.
- Concrete Spring services are the default. Introduce an interface when there is a real boundary, such as a capability consumed across features, multiple implementations, or a dependency that benefits from substitution. Do not require an interface plus `Impl` class for every service.
- Keep logic private to its owning service unless it has a distinct responsibility or meaningful reuse.

### Repositories

- Repositories own MongoDB access, query construction, update predicates, index-sensitive operations and persistence-specific behavior.
- Expose typed, operation-oriented methods to services. Keep `MongoTemplate`, `Query`, `Criteria` and raw BSON details inside the persistence boundary.
- Preserve atomicity, uniqueness and conditional-write behavior. A pre-check followed by an unconditional write is not a substitute for a database-enforced uniqueness or atomic operation when concurrent requests are possible.

### DTOs, models and mapping

- Keep HTTP DTOs separate from persistence documents so API evolution does not accidentally change stored data.
- Java records are suitable for immutable request/response DTOs and may be used for MongoDB documents when the chosen Spring Data mapping and required persistence behavior support them.
- Do not require all persistence documents to extend a common base class, or require Lombok annotations on every model.
- Use classes when mutability, framework behavior or lifecycle requirements make them a better fit; choose the representation intentionally.
- Introduce a mapper only when transformation logic is sufficiently complex, repeated or independently meaningful. Simple construction does not require a dedicated mapper class.

## 3. Dependency injection

- Prefer constructor injection for required Spring dependencies.
- Store required dependencies in `private final` fields.
- Plain constructors are acceptable. Lombok constructor annotations may be used when they improve consistency and remain clear.
- Do not use field injection or nullable fallbacks for required dependencies.
- Avoid introducing Spring beans for pure functions that have no dependency or lifecycle requirement.

## 4. Validation and error handling

- Validate external request shape at the HTTP boundary. Use Bean Validation and `@Valid` where they make constraints clearer and keep validation consistent.
- Services must still enforce business invariants for non-HTTP entry points, including producer ingestion and scheduled/background work.
- Controllers should translate known application failures into the HTTP error format defined by the API contract.
- Use `@RestControllerAdvice` for shared HTTP exception translation where appropriate. Keep exception types meaningful and do not expose persistence/framework exceptions as public API errors.
- Do not duplicate the same validation indiscriminately across controller, service and repository layers. Each layer should validate the conditions it owns.

## 5. MongoDB and data integrity

- Preserve the distinction between event time (`occurredAt`) and server receipt time (`receivedAt`).
- Stable client/producer-generated IDs are the identity of retryable write records. Retrying the same logical record must not create a duplicate or double-count a metric.
- Database uniqueness is the final protection against concurrent duplicate inserts. An identical retry may be acknowledged as a duplicate; reusing an ID with a different payload must remain a conflict.
- Raw accepted events/records are the source of truth. Daily totals and dashboard values are derived by the backend, not trusted from client- or producer-computed aggregates.
- Keep MongoDB-specific query and update mechanics inside repositories.
- Do not rename persisted fields, change ID representations, alter document shapes or rebuild indexes as incidental cleanup. Such changes require an explicit data-compatibility decision and an appropriate migration, targeted reset or reseed plan.
- Use explicit `@Field` mappings when a stored field name must differ from the Java property name or when the storage contract intentionally requires an explicit mapping. Do not impose a repository-wide snake_case migration by convention alone.

## 6. Time and daily aggregation

- Preserve the current daily statistics timezone, `Asia/Ho_Chi_Minh`, and its local calendar-day boundaries unless an API/business decision explicitly changes them.
- Use `Instant` for persisted event instants when the source timestamp represents an absolute point in time. Preserve the source offset when it is required by an external contract; do not silently reinterpret local timestamps.
- Use an injected `Clock` for business logic that depends on the current time, so boundary behavior can be tested deterministically.
- Keep timezone/day-boundary policy in one well-defined place as the backend evolves. Avoid scattering independent timezone literals across services.
- Each metric retains its own aggregation meaning. For example, step increments are summed, while heart-rate observations are selected by event time; do not generalize all metrics into one aggregation rule.

## 7. API compatibility

- The API contract is the source of truth for endpoint paths, request/response shapes, statuses, error codes, timestamp semantics, idempotency and daily-statistics behavior.
- Make API changes deliberately and update the contract when behavior changes.
- Consider the separate Mobile and Data Producer repositories as API consumers. A backend-only implementation change can still be a cross-repository breaking change.
- Do not add authentication, user-level isolation, historical queries, asynchronous infrastructure or other deferred capabilities without an approved scope.

## 8. Tests

- Test business rules and edge cases at the service/use-case boundary.
- Cover idempotency, conflicting IDs, time boundaries, aggregation and validation when those behaviors are changed.
- Use repository/integration tests when database semantics, query behavior, indexes or atomicity are important to the change.
- Keep tests deterministic. Prefer an injected `Clock` over assumptions about the machine's current time.
- Do not add tests merely to mirror package moves, generated constructors or trivial delegation.

## 9. Comments and adoption

- Names and structure should carry the intent. Comments should explain only non-obvious reasons, invariants, ordering constraints or trade-offs.
- Do not add boilerplate comments that repeat method signatures or code behavior.
- Apply these conventions to new code and code touched by an approved refactor. Do not use a convention-adoption task to rewrite unrelated legacy code.
- Documented rules and automated checks are different things. Do not claim a rule is enforced automatically unless a test or build check actually enforces it.
