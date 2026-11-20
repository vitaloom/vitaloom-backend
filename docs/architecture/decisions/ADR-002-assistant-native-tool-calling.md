# ADR-002 — Assistant native tool calling

Status: Accepted on 2026-11-20

The approved Astra assessment and P1–P5 plan select Spring AI 2.0.2 `ChatClient` and its native `ToolCallingAdvisor` for the course conversation. Gemini may return prose or ask for missing details. The Backend supplies the trusted receive time and bounded role history, checks active access on every dispatch, and verifies publishable claims against current evidence.

The journal tools consume normalized Water and MAIN Sleep arguments. A tool can create only after winning the current turn and revision reservation. The owning domain validates and persists the entry. The Backend settles the canonical answer in Mongo before returning a tool result. An unanswered claim retains the possibility that the domain committed, so replay never resubmits the create.

This supersedes the literal-argument matching in DL-0017 and DL-0022 and the implementation's required safety and answer tool protocol. It retains their unified text and voice flow and single-create rule, the permissions in DL-0018 and DL-0020, and the [public conversation envelope](../../api/assistant-course.md). Historical answers remain readable.

Each proposed sensitive action and arbitrary final prose receives a tool-free policy check. This adds provider calls and remains a semantic check. The owning services, receipt reservation and authorization checks enforce the deterministic write and access boundaries.
