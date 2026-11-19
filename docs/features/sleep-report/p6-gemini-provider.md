# P6 Gemini Story and Explanation provider

Sleep Recent Story and Explanation use the Gemini Developer API under Spring's default profile. The default enable switch is on; setting `VITALOOM_SLEEP_GEMINI_ENABLED=false` disables the provider. A valid `GEMINI_API_KEY` is required when the provider is enabled. There is no separate profile or approval variable for Gemini. The provider is excluded from explicit `prod` and `production` profiles. The deterministic provider is test-only and requires `VITALOOM_SLEEP_STORY_ENABLED=true`.

Existing account authentication, opt-in consent, current READY evidence, deterministic fallback and HTTP response shapes apply to both features. Story and Explanation preserve the account-level consent revision checks before dispatch and before publication.

## Provider request and publication

The adapter uses the `gemini-3.1-flash-lite` model and sends the allowlisted numeric evidence needed to select a story insight or explanation framing. It does not send account identifiers, source references, raw journal entries, dates, time zones, or free text. The request contains one response candidate, a small token cap, and no tools or grounding.

Structured Story output chooses one or two current insight codes with at least one code other than A01. Structured Explanation output chooses a framing and restricts the schema's insight code to the requested READY insight. The Backend renders Vietnamese text from evidence-derived templates and validates content, the word limit, current evidence and consent before publication. Withdrawn or changed consent suppresses output.

Both flows allow four attempts per rolling minute and a default twelve-attempt limit for the active provider instance. Use `VITALOOM_SLEEP_GEMINI_MAX_DISPATCHES` with a value from 1 to 12 to lower the process limit. Application retries consume capacity. Requests are capped at 8 KiB, responses at 32 KiB and model output at 128 tokens. These limits apply to one Backend process and reset when the provider is recreated.

## Default-profile activation and live smoke

Add `GEMINI_API_KEY` to the Backend's untracked `.env` or provide it through the process environment/secret manager. Leave `SPRING_PROFILES_ACTIVE` unset or set it to `default`. `VITALOOM_SLEEP_GEMINI_ENABLED` can be omitted or set to `true`; set it to `false` to disable the provider. Start Redis at `REDIS_HOST`/`REDIS_PORT` (localhost/6379 by default). The Sleep API still requires account consent and current verified READY evidence with at least one insight beyond A01.

The opt-in `GeminiSleepStorySmokeIT` test sends two requests with synthetic evidence directly to Gemini; it starts neither the application nor a database. It is excluded from Maven's default test selection. Provide `GEMINI_API_KEY` and set `VITALOOM_SLEEP_GEMINI_ENABLED=true` in the process environment, then run:

```powershell
.\mvnw.cmd '-Dtest=GeminiSleepStorySmokeIT' test
```

The test verifies a live Story and Explanation response from the configured key. It does not exercise HTTP authentication, stored consent, MongoDB evidence or Redis. The local HTTP flow is covered separately by the existing Sleep integration tests.
