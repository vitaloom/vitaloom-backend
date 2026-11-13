# Authentication API Contract

**Status:** Canonical MVP authentication contract  
**Implementation baseline:** `note/authentication-v1/03-api-contract-auth.md`

The implementation baseline for this section is `note/authentication-v1/03-api-contract-auth.md`. All endpoints use JSON. The authenticated user shape is `{ "id": "<Mongo id>", "email": "name+tag@example.com" }`. Email is trimmed at the edges and compared case-insensitively; dots and plus tags are retained. Passwords are never trimmed, and registration requires at least eight characters.

| Endpoint | Auth | Success | Failure |
|---|---|---|---|
| `POST /api/auth/register` `{ "email": "...", "password": "..." }` | Public | `201` `{ "user": {"id":"...","email":"..."}, "accessToken":"...", "refreshToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `409 EMAIL_ALREADY_REGISTERED` |
| `POST /api/auth/login` `{ "email": "...", "password": "..." }` | Public | `200` same token and user shape as register | `400 VALIDATION_ERROR`; `401 INVALID_CREDENTIALS` |
| `POST /api/auth/refresh` `{ "refreshToken": "..." }` | Public | `200` `{ "accessToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `401 INVALID_REFRESH_TOKEN` |
| `GET /api/auth/me` | Bearer access JWT | `200` `{ "id":"...", "email":"..." }` | `401 UNAUTHORIZED` |

If registration persisted the account but token issuance fails, return `201` `{ "user": {"id":"...","email":"..."}, "authStatus":"LOGIN_REQUIRED" }`. The client offers login rather than retrying registration. Request-level failures use the RFC 9457 format and status/code rules in the [API Contract Index](./api-contract.md#generic-error-response); authentication validation uses `400 VALIDATION_ERROR`. Login uses the same error code for unknown email and wrong password.

Access JWTs expire after 15 minutes; refresh JWTs after 30 days. They use separate signing keys from environment configuration and distinct token-type claims. A refresh JWT is never accepted as a Bearer access credential. Refresh returns only a new access JWT and does not rotate the refresh JWT. `GET /api/health` stays public. Heart Rate and Steps batch ingestion and all of their Statistics routes require Bearer access JWTs and scope data by the JWT subject. Water Tracking routes also require a Bearer access JWT and scope data by the JWT subject. Sleep Journal F1 requires a Bearer access JWT and scopes records by its subject.

## Current and contracted route-level security

| Route group | Required access policy | Implementation status |
|---|---|---|
| `POST /api/auth/register`, `/login`, `/refresh` | Public | Implemented |
| `GET /api/auth/me` | Bearer access JWT required | Implemented |
| `GET /api/health` | Public | Implemented |
| `POST /api/heart-rate/batch` | Bearer access JWT required; account comes from JWT subject | Implemented |
| `/api/statistics/heart-rate/**` | Bearer access JWT required; reads are scoped to JWT subject | Implemented |
| `POST /api/steps/batch` | Bearer access JWT required; account comes from JWT subject | Implemented |
| `/api/statistics/steps/**` | Bearer access JWT required; reads are scoped to JWT subject | Implemented |
| `/api/water/**` | Bearer access JWT required; logs and summaries are scoped to JWT subject | Implemented |
| `/api/sleep/v2/**` | Bearer access JWT required, scoped to JWT subject | Implemented |
| Cross-metric dashboard | Account-scoped access required before real multi-user use | Not yet enforced |

Do not infer that a documented endpoint is implemented or safe for real multi-user private health data merely because an authentication flow exists. Each metric contract must state its route-level access behavior.
