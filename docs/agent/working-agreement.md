# Working agreement

This document defines how backend work is performed, independently of the issue tracker, branch strategy or agent tool being used. Completion criteria are defined in [Definition of Done](definition-of-done.md).

## 1. Understand the task

1. Confirm the requested behavior, scope and acceptance criteria.
2. Inspect the relevant source code, tests, configuration and API/persistence documentation before editing.
3. Trace affected callers and consumers. Backend changes may affect the separate Mobile and Data Producer repositories.
4. Find the existing business or API decision before relying on an assumption. Do not invent health-domain rules or silently reinterpret measurements.
5. Identify compatibility concerns, especially event IDs, retry behavior, timestamps, daily aggregation and response shape.

If an ambiguity materially changes externally observable behavior, persisted data, compatibility or acceptance criteria, surface it before implementation. Record minor assumptions explicitly.

## 2. Implement in small, reviewable steps

- Keep each change focused on the approved scope.
- Prefer the smallest design that gives the behavior a clear owner.
- Add or update automated tests for meaningful behavior and regression risks.
- After each meaningful step, run the narrowest relevant checks and inspect their output.
- Keep unrelated formatting, cleanup and package restructuring out of the change.
- Update API, flow or architecture documentation when the implementation changes the contract or an established design decision.

## 3. Failure and retry discipline

After a failed operation, inspect the error and classify the likely cause before retrying:

- implementation or test failure;
- environment or dependency issue;
- tool invocation or command mistake;
- transient external failure.

Before retrying, state what was learned and what will change in the next attempt. Do not repeat the same operation with the same inputs and assumptions without new evidence or a concrete reason.

If the same operation produces the same failure twice, stop repeating it and investigate the underlying cause. Inspect the relevant file state, inputs, command syntax, environment and previous output. The next attempt must use a justified change in approach or explain why an identical retry is appropriate.

If the approach continues to fail, switch strategies or report the blocker. Do not keep retrying merely to appear productive.

Reuse valid verification results while the relevant code, configuration, inputs and environment remain unchanged. Rerun checks when a relevant change invalidates previous evidence, a failure is reasonably suspected to be transient, or another concrete reason justifies it.

## 4. Verification discipline

- Use the build and test commands that actually exist in the current checkout. Do not assume a Maven wrapper or project structure exists before inspecting it.
- During implementation, prefer focused unit or integration tests that answer a specific question.
- For application code or configuration changes, run the project's full change-level verification command before declaring the change fully verified when the environment supports it. For Maven projects this will normally be the repository's documented `verify` command.
- For documentation-only changes, verify the changed paths, links, commands and examples as appropriate. Do not run the application test suite without a reason.
- Do not create disposable tests or repeatedly run full builds unless they resolve a concrete uncertainty.

A checked task box is a status record, not proof that a check ran.

## 5. Finish with evidence

The final report must distinguish:

- checks actually run and their observed results;
- checks that failed or were not run, including why;
- manual actions still required;
- known limitations or unresolved blockers.

Never claim that a command, test, build or manual flow passed unless its result was observed. If required verification cannot run, report the task as not fully verified rather than implying it is Done.
