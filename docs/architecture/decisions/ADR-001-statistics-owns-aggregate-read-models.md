# ADR-001: Statistics Owns Aggregate Read Models

- **Status:** Accepted
- **Date:** 2026-11-10
- **Scope:** VitaLoom Backend modular monolith

## Context

VitaLoom stores raw health measurements in metric-specific features such as Heart Rate and Steps. Those records have metric-specific ingestion, validation, identity, persistence and query requirements.

Derived values have a different responsibility. Daily averages, totals, time-bucketed series and dashboard summaries are calculated views of raw records. Allowing each metric feature to own both raw persistence and its derived aggregates would split aggregation ownership across modules and make cross-metric statistics harder to compose consistently.

The backend needs one reusable ownership rule for all metrics, even though the MVP does not currently require precomputed aggregate storage.

## Decision

### 1. Metric features own raw records

Each metric feature (`heartrate`, `steps`, `sleep`, `water`) owns:

- Its raw input/ingestion behavior and metric-specific validation.
- Its raw persistence model, MongoDB collection, indexes and write guarantees.
- Raw-record identity, duplicate/conflict handling and other raw-data invariants.
- Provider-owned raw-data read contracts required by Statistics.

Metric features remain independent of Statistics. They do not calculate or persist derived aggregate read models.

### 2. Statistics owns all derived aggregates

The `statistics` feature owns:

- Metric-specific aggregate calculations and their meaning.
- Time-bucketed or other aggregate series derived from raw records.
- Cross-metric composition for dashboard and overview responses.
- All derived aggregate/read-model values, whether they describe one metric or combine multiple metrics.

Examples of Statistics-owned calculations include Heart Rate daily average/latest observation, Steps totals, Sleep duration and Water intake totals. These examples establish ownership; they do not independently add API requirements or settle every metric's business formula.

Statistics obtains source records only through the owning metric feature's provider read contract. It must not depend on the feature's ingestion service interface, inject another feature's repository, access its persistence model directly or issue raw MongoDB queries against another feature's collection.

### 3. Current MVP phase: calculate on read

For the current MVP, Statistics calculates the required aggregate values when a read request is processed, using raw metric records as the source of truth.

The MVP does not introduce:

- Precomputed aggregate collections.
- Statistics-owned aggregate persistence models or repositories.
- Scheduled/background aggregation workers.
- Refresh, invalidation or replay infrastructure for aggregates.

This is an implementation-phase choice, not a change in ownership: aggregate calculations already belong to Statistics even when their results are calculated on demand and not persisted.

### 4. Accepted future direction: precomputed aggregates may be owned by Statistics

If a concrete performance or product requirement later justifies precomputation, Statistics will own the resulting aggregate read models and their persistence lifecycle, including their schemas, repositories, update/recalculation behavior and consistency policy.

Metric features will continue to own raw records. The raw records remain the source of truth; precomputed aggregates are derived data.

This ADR does not choose when to introduce precomputation or how to implement scheduling, event-driven updates, late-arriving-data correction, replay, invalidation or backfills. Those mechanisms require a separate design decision when the need becomes concrete.

## Consequences

### Positive

- Raw-data ownership stays close to each metric's ingestion and persistence rules.
- Aggregate calculations and their semantics have one clear owner across all metrics.
- Cross-metric Statistics can compose metric query capabilities without bypassing feature boundaries.
- The MVP avoids premature aggregate persistence and its consistency overhead.
- A later precomputation phase can be added inside Statistics without transferring raw-data ownership.

### Trade-offs

- Calculate-on-read may become more expensive as raw data volume and read traffic grow.
- Statistics needs sufficiently useful raw-data query capabilities from each metric feature.
- The implementation must keep metric-specific aggregation rules explicit; a single generic aggregation formula must not be imposed on all metrics.

## Implementation guidance

- Keep the current package layout and dependency direction in [Package Structure](../package-structure.md).
- Keep API paths and external request/response behavior in the [API Contract](../../api/api-contract.md). This ADR does not by itself rename endpoints or authorize an API compatibility change.
- Decide whether a time-series endpoint returns raw measurements or aggregate buckets before assigning its implementation. Raw measurement access remains owned by the metric feature; calculations and aggregate buckets remain Statistics-owned.
- When precomputation becomes necessary, create a focused follow-up ADR/design for the trigger, update strategy, consistency guarantees, late data, backfill and operational behavior before implementation.
