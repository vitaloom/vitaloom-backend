# Assistant sandbox API

Status — Internal sandbox contract for AA-110. Deployed locally with disposable MongoDB for one passing live Gemini HTTP check.

## POST /api/assistant/chat

Send a Bearer access JWT for a synthetic account with the current Assistant permission enabled. Send the fields below.

```json
{
  "turnId": "4d61401c-35a7-4ca2-a5e4-54124dfcadfd",
  "text": "Mình xem nhật ký giấc ngủ ở đâu?"
}
```

Generate a UUID for each new turn and keep it when replaying that turn. Text must be nonblank and at most 300 UTF-16 code units. The optional legacy boolean `consentAccepted` has no effect. The backend matches the approved question after trimming and NFC normalization, ignoring case. The only provider input is the fixed Vietnamese app-help fixture.

Successful fixture response:

```json
{
  "turnId": "4d61401c-35a7-4ca2-a5e4-54124dfcadfd",
  "outcome": "ANSWERED",
  "answer": "Mở mục Giấc ngủ rồi chọn Nhật ký.",
  "citations": [
    {"id": "app-help-sleep-journal", "version": 1, "title": "Nhật ký giấc ngủ", "route": "/sleep/journal"}
  ]
}
```

Other 200 outcomes have a localized answer and empty citations.

| Outcome | Condition |
| --- | --- |
| `INSUFFICIENT_EVIDENCE` | The message falls outside the approved app-help fixture. |
| `DEPENDENCY_UNAVAILABLE` | The provider failed or returned an unusable answer. The same turn ID replays the outcome. |

Request errors use [Problem Details](api-contract.md#generic-error-response). Missing or invalid JWT returns 401. An account without the current Assistant permission, or outside the configured account restriction, gets 403. With a valid JWT, the disabled endpoint returns 404. Invalid JSON or fields return 400. An in-flight or unresolved claim returns 409 `ASSISTANT_TURN_PENDING`. A failure reading or writing the claim returns 503 `ASSISTANT_UNAVAILABLE`.

The claim is created before dispatch and is unique per JWT subject and turn UUID. An identical eligible replay uses the saved result within the 24-hour retry window. Keep the same ID on uncertainty and stop replaying after 24 hours. An expired record may still block a replay until MongoDB removes it. The record contains the claim key, outcome and expiry.

## Sandbox setup

Set `ASSISTANT_GEMINI_API_KEY` in the Backend environment or its untracked `.env` and start Spring Boot normally. `ASSISTANT_SANDBOX_ACCOUNT_ID` optionally restricts this endpoint to one JWT subject. The `prod` and `production` profiles exclude the route. Gemini uses the Developer API with one attempt, model `gemini-3.1-flash-lite`, 128 output tokens and a 10-second transport timeout. Call the fixture endpoint directly from the test harness. The Mobile app uses the [conversation API](assistant-course.md).
