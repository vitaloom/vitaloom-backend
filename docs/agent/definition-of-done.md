# Definition of Done

A task is Done when the common criteria and all applicable task-type criteria are met. If required verification fails or cannot run, report the task as blocked or not fully verified rather than implying it is Done.

## Common criteria

- [ ] The agreed scope and acceptance criteria are satisfied; unrelated changes are excluded.
- [ ] Relevant behavior, compatibility and failure paths have been considered.
- [ ] Appropriate checks have actually been run and their observed results are reported accurately.
- [ ] Existing tests and checks have not been weakened, disabled, suppressed or bypassed to obtain a pass.
- [ ] Relevant API, persistence, flow and setup documentation matches the resulting implementation.
- [ ] New or refactored code follows the applicable implementation conventions; known legacy gaps are identified rather than silently copied.
- [ ] Remaining risks, unrun checks and required manual actions are disclosed.

## Behavior change or bug fix

- [ ] The intended behavior and important boundary cases are verified in proportion to the risk.
- [ ] A regression test is added when practical; if not, explain why.
- [ ] API behavior, error responses and consumer-visible changes are checked.
- [ ] API contract or flow documentation is updated when the externally observable behavior changes.

## Persistence or data-contract change

- [ ] Stored identifiers, field mappings, indexes, uniqueness and idempotency behavior are checked as applicable.
- [ ] Existing records and retry behavior remain compatible unless a behavior change is explicitly approved.
- [ ] Any required migration, reset or reseed is identified separately; destructive data changes are not assumed.
- [ ] The effects on daily aggregation and timestamp interpretation are verified where relevant.

## Refactor or structural change

- [ ] Behavior is preserved unless an explicit requirement says otherwise.
- [ ] Relevant package boundaries and dependency direction are reviewed.
- [ ] API serialization, persistence mapping, configuration and transaction behavior are checked when affected.
- [ ] The change is limited to the approved structure; unrelated cleanup is excluded.

## Build, dependency or configuration change

- [ ] The project builds and relevant verification checks pass.
- [ ] Added or changed dependencies and configuration are necessary and scoped.
- [ ] Startup behavior and environment-specific settings are checked when affected.
- [ ] Secrets, personal credentials and environment-specific values are not committed.

## Documentation-only change

- [ ] Instructions describe the current implementation or clearly label a proposed/future state.
- [ ] Changed links, paths, commands and examples are checked when practical.
- [ ] Application tests are run only when the documentation change affects code/configuration or there is another clear reason.

## Verification standard

Use the current project's actual build entry point. For an established Maven backend, run the documented full verification command (normally `./mvnw verify` or its platform equivalent) before declaring an application-code change fully verified, when the environment supports it.

During implementation, run faster relevant checks after meaningful steps. A checklist, task status or agent-generated summary is not evidence that a command ran. Record the command and its observed result.
