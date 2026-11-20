# Assistant READY FAQ with Gemini

Assistant conversation requests require a bearer JWT and the current version of the account-wide permission. Mobile shows the Google Gemini data disclosure before enabling the existing Profile toggle. Old local-only grants are ineffective until the user enables permission with the new disclosure.

When authorized, Gemini selects request-scoped tools through Spring AI and replies in ordinary prose. Backend checks the proposed reply against relevant excerpts and fresh personal facts, then binds sources used in the reply to Backend-owned metadata. READY retrieval uses exact vector search, top 10 and a 0.75 similarity floor, then rechecks approval and content version. Release also requires support for the actual claim.

## Deployment

- `ASSISTANT_GEMINI_API_KEY` currently connects to the Gemini Developer API. The presence of a key does not establish the Google project, billing tier, retention or data-use settings. `ASSISTANT_VECTOR_MONGO_URI` enables optional READY retrieval.
- `ASSISTANT_GEMINI_HEALTH_PROCESSING_VERIFIED` defaults to `false`. The personal request and voice paths return 503 before external dispatch until the actual deployment configuration has been verified for this use. Set it only for an eligible deployment or an isolated synthetic fixture run.
- The [Gemini Developer API terms](https://ai.google.dev/gemini-api/terms) prohibit submitting sensitive/personal content to Unpaid Services and describe training use and potential human review. Paid Services exclude prompts and responses from product improvement, but permit limited safety logging and processing across locations. Verify the Cloud Billing project, regional applicability, age/use restrictions, contractual handling and logging/data sharing settings before dispatching real health data. General wellness support stays within the approved non-clinical scope.
- The current validation uses synthetic users and synthetic health records.
