# Backend documentation

Start with the [repository README](../README.md) for setup and commands. Then use this index to find the source of truth for the task; check implementation status before describing an endpoint as available.

| Need | Source |
| --- | --- |
| Package ownership, current dependencies and planned architecture | [Package structure](architecture/package-structure.md) |
| Implementation patterns and persistence behavior | [Implementation conventions](architecture/implementation-conventions.md) |
| Why aggregate ownership belongs to Statistics | [ADR-001](architecture/decisions/ADR-001-statistics-owns-aggregate-read-models.md) |
| API paths, payloads, statuses and shared API rules | [API contract index](api/api-contract.md) and its linked area/metric contracts |
| Water feature behavior and implementation notes | [Water feature docs](features/water/README.md) |
| Task workflow and completion criteria | [Working agreement](agent/working-agreement.md), [Definition of Done](agent/definition-of-done.md) |

Architecture documents own shared package and coding rules; the API contracts own wire behavior; feature documents own feature-specific guidance. Link to the owner instead of copying its details. A documented or planned contract does not establish that its endpoint is implemented.
