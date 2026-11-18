# Assistant READY FAQ with Gemini

The Mobile composer uses the existing authenticated `/api/assistant/mvp/conversations` endpoints. Conversation reads and writes remain scoped to the bearer token's subject. Gemini dispatch requires `geminiAccepted: true` for the current turn. An authenticated account can use the course route without an Assistant account allowlist.

Configure these two Assistant settings on the Backend:

| Setting | Purpose |
| --- | --- |
| `ASSISTANT_GEMINI_API_KEY` | Gemini Developer API key. Keep it on the Backend. |
| `ASSISTANT_VECTOR_MONGO_URI` | Atlas connection string with an explicit database, e.g. `mongodb://127.0.0.1:27183/assistant_vector?directConnection=true` for Atlas Local. |

Start the Backend normally. The READY path uses `gemini-embedding-2` at 768 dimensions, `gemini-3.1-flash-lite`, the `assistant_ready_faq_v1` index and top 10 search. It passes up to three eligible FAQs to one generation request. A per-turn opt-out or a missing search configuration uses the local path. The public FAQ path uses the current question and public corpus. Keep manual tests synthetic and omit personal health information from questions sent to Gemini.

The separate `POST /api/assistant/chat` fixture endpoint has its own historical test controls. It is not used by the READY conversation path.

Questions about the user's personal Sleep or Water records are handled within the Backend using the authenticated account's owning services. These turns require the account-level personal-data setting. Change it through `/api/assistant/mvp/settings`. The compatibility route for conversation permission cannot grant access independently. Resolved personal turns use the local services. The model receives neither the question history nor the results of a personal-data read. With separate per-turn Gemini consent, the existing tool-selection fallback can receive the current question only.

The conversation stores a small routing context with a resolved personal turn. It records the metric, requested time window and whether the user asked for a summary, meaning or an anomaly check. Follow-ups can reuse that context for 30 minutes. Topic corrections retain the positive metric named by the user. An explicit change to Water or Sleep selects the newly named metric. When the message names both metrics without a clear correction, the Assistant asks the user to choose.

Recent Sleep replies use the authenticated account's [Recent Sleep Report](../features/sleep-report/api-contract.md#get-apisleeprecent). A question about “this week” shows only verified days from Monday through today in that existing report. The report's rolling seven-day average is not relabeled as a calendar-week average. Descriptive results do not apply an app-defined anomaly threshold.

When a personal Sleep turn also asks for health guidance, the Backend sends a fixed public READY FAQ query and accepts only the reviewed Sleep education IDs `READY-MANUAL-001` and `READY-MANUAL-002`. It publishes the approved FAQ text after Gemini selects a verified source. A missing match remains insufficient evidence. A retrieval or provider failure is reported as unavailable alongside the personal result.
