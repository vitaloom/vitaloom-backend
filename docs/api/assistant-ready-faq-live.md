# Assistant READY FAQ with Gemini

The Mobile composer uses the existing authenticated `/api/assistant/mvp/conversations` endpoints. Conversation reads and writes remain scoped to the bearer token's subject. Gemini dispatch requires `geminiAccepted: true` for the current turn. An authenticated account can use the course route without an Assistant account allowlist.

Configure these two Assistant settings on the Backend:

| Setting | Purpose |
| --- | --- |
| `ASSISTANT_GEMINI_API_KEY` | Gemini Developer API key. Keep it on the Backend. |
| `ASSISTANT_VECTOR_MONGO_URI` | Atlas connection string with an explicit database, e.g. `mongodb://127.0.0.1:27183/assistant_vector?directConnection=true` for Atlas Local. |

Start the Backend normally. The READY path uses `gemini-embedding-2` at 768 dimensions, `gemini-3.1-flash-lite`, the `assistant_ready_faq_v1` index and top 10 search. It passes up to three eligible FAQs to one generation request. A per-turn opt-out or a missing search configuration uses the local path. The public FAQ path uses the current question and public corpus. Keep manual tests synthetic and omit personal health information from questions sent to Gemini.

The separate `POST /api/assistant/chat` fixture endpoint has its own historical test controls. It is not used by the READY conversation path.
