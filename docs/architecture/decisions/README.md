# Architecture Decision Records

This directory records significant architecture decisions for VitaLoom Backend so contributors and coding agents can discover both the decision and the reasoning behind it.

## How to use these records

- Read the relevant accepted ADR before changing the ownership, dependency direction or trade-offs it defines.
- `Accepted` means the architectural direction is agreed and should guide implementation. It does not mean every future phase described by the ADR is already implemented.
- Each ADR must distinguish the current implementation phase from accepted future direction and from details that remain undecided.
- When a decision is replaced, mark the old ADR as `Superseded` and link the replacement. Do not silently rewrite history.
- Keep implementation-specific details in the relevant architecture, API or feature design document. ADRs explain the decision and its consequences; they are not substitutes for those documents.
- Add an ADR when a decision materially affects module ownership, dependencies, persistence boundaries, consistency, compatibility or a significant technical trade-off. Do not create an ADR for every small implementation choice.

## Accepted decisions

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](./ADR-001-statistics-owns-aggregate-read-models.md) | Statistics owns derived aggregate calculations and read models; the MVP calculates on read | Accepted |
