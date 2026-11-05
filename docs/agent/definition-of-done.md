# Definition of Done

A task is Done when the common criteria are met and the criteria relevant to its change type are satisfied. Apply verification in proportion to the change's scope and risk; this checklist is not a requirement to run every kind of test for every task.

## Common criteria

- [ ] The agreed scope and acceptance criteria are satisfied; unrelated changes are excluded.
- [ ] The implementation follows the applicable conventions and preserves existing behavior outside the approved scope.
- [ ] Relevant checks have been performed, and their actual results are reported accurately.
- [ ] Related API, persistence, flow or setup documentation is updated when the change affects it.
- [ ] Known limitations, unverified areas and remaining manual steps are disclosed.

## Additional criteria when applicable

### Behavior change or bug fix

- [ ] The intended behavior and important edge cases are checked.
- [ ] A focused regression test is added when practical, especially for a reproducible bug; when it is not practical, explain the alternative verification.
- [ ] Consumer-visible behavior, including HTTP status and error response, is checked when affected.

### Persistence or data-contract change

- [ ] Relevant identifiers, uniqueness, idempotency, field mapping and stored-data compatibility are checked.
- [ ] Timestamp interpretation, timezone boundaries and aggregation are checked when affected.
- [ ] Any required migration, reset or reseed is identified explicitly. Do not assume destructive data changes are acceptable.

### Refactor or structural change

- [ ] Existing behavior is preserved unless the change explicitly intends otherwise.
- [ ] Affected dependencies, package boundaries and integration points are checked.
- [ ] Serialization, persistence mapping or configuration compatibility is checked when affected.

### Build, dependency or configuration change

- [ ] Relevant build and startup checks pass.
- [ ] Added or changed dependencies and configuration are necessary and scoped.
- [ ] Secrets and machine-specific credentials are not committed.

### Documentation-only change

- [ ] Documentation matches the current implementation or clearly labels proposed/future behavior.
- [ ] Changed paths, links, commands and examples are checked when practical.
- [ ] Application tests are run only when the documentation change affects code/configuration or there is another concrete reason.

## Verification standard

Choose checks based on the risk and scope of the change:

- Use focused tests during implementation to validate the behavior being changed.
- Run the full test suite or project verification for changes with broad impact, shared behavior, persistence/data-contract risk, significant configuration/build changes, or before merging a substantial change when the environment supports it.
- A trivial, isolated change does not automatically require a full build and test run.
- Do not add tests merely to reach an arbitrary coverage percentage or to test trivial delegation. Prefer tests that protect meaningful behavior, edge cases and regressions.
- Never weaken, disable, suppress or bypass an existing check to obtain a pass.

If an important check cannot run, state why and identify what remains unverified. Do not claim full verification without evidence. A checklist or task status is not proof that a command ran.
