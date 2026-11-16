# P6 Gemini Developer API sandbox

The Sleep API can use the stable `gemini-3.1-flash-lite` model in an isolated, synthetic-data sandbox. The provider is disabled by default. The sandbox profile excludes `prod` and `production`. The fake and Gemini profiles use separate cache identities. Existing authentication, opt-in consent, READY evidence, deterministic fallback and HTTP response shapes apply to both.

## Provider request and publication

The adapter uses the stateless Gemini Developer API `generateContent` endpoint over HTTPS with a restricted AI Studio key in the `x-goog-api-key` header. The Story request projects the complete READY pool into catalog codes and allowlisted numeric aggregates. Explanation projects one READY insight. Relative labels distinguish comparison periods. Account identifiers, fingerprints, dates, time zones, free text, raw journal data and source references stay in VitaLoom. Requests use one response candidate, a small token cap, and no tools or grounding.

Structured Story output chooses one or two current insight codes with at least one code other than A01. Structured Explanation output chooses a framing and restricts the schema's insight code to that request's READY insight. The adapter rejects malformed JSON, unexpected schema fields, blocked or incomplete output and unsupported choices. Backend renders Vietnamese text from evidence-derived templates and validates content, the word limit, current evidence and consent before publication. For a withdrawn consent revision, the service suppresses publication and new cache writes. A dispatch already sent to Gemini remains subject to Google's processing and retention.

Both flows share a local dispatch limit of four attempts per rolling minute and a default twelve-attempt budget for the active provider instance. Use `VITALOOM_SLEEP_GEMINI_SANDBOX_MAX_DISPATCHES` with a value between 1 and 12 to bound a sandbox run further. Application retries consume capacity. The JSON request is capped at 8 KiB, the response at 32 KiB and the requested model output at 128 tokens. Exhaustion falls back through the existing API path. The limits apply to one Backend process and reset when the provider is recreated. The project operator controls paid quota and spend separately in Google AI Studio. Single-flight shares work within one Backend instance.

## Local activation and live smoke

After the owner approves the isolated run, its cost and provider privacy/retention review, use a dedicated AI Studio project and an isolated fixture containing only synthetic data. Verify the project quota and available spend. Keep optional project prompt/response logging and data sharing disabled. Google may retain Paid Services data for abuse monitoring. Its terms describe additional uses of prompts and responses on the unpaid tier. Use a Gemini-restricted AI Studio authorization key and supply it as `GEMINI_API_KEY` through the process environment or a local secret manager. Keep HTTP request/response and header logging off.

For the local Backend application, activate Spring profile `sleep-ai-gemini-sandbox` and set `VITALOOM_SLEEP_GEMINI_ENABLED=true` and `VITALOOM_SLEEP_GEMINI_SANDBOX_APPROVED=true`. Use the approval flag only after the checks above are complete. Existing user consent storage still applies, so use a separate synthetic account and its explicit opt-in if exercising the Backend HTTP endpoints.

Run the live smoke from the Backend root with both environment flags set after approval and the key injected. It exercises the adapter directly with two synthetic requests and starts neither the application nor a database. Maven's default test selection excludes this class.

```powershell
mvn '-Dtest=GeminiSleepStorySmokeIT' test
```

Simulated verification and any pending live gate are recorded in the [P6 task record](../../../../vitaloom-business-docs-and-tools/docs/development-workflow/tasks/sleep-p6-real-provider.md).

Google references: [GenerateContent REST](https://ai.google.dev/api/generate-content) · [Structured output](https://ai.google.dev/gemini-api/docs/generate-content/structured-output) · [Model](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite) · [API keys](https://ai.google.dev/gemini-api/docs/api-key) · [Terms](https://ai.google.dev/gemini-api/terms) · [Project logging](https://ai.google.dev/gemini-api/docs/logs-policy) · [Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).
