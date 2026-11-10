# Authentication API Contract

**Status:** Canonical MVP authentication contract  
**Implementation baseline:** `note/authentication-v1/03-api-contract-auth.md`

The implementation baseline for this section is `note/authentication-v1/03-api-contract-auth.md`. All endpoints use JSON. The authenticated user shape is `{ "id": "<Mongo id>", "email": "name+tag@example.com" }`. Email is trimmed at the edges and compared case-insensitively; dots and plus tags are retained. Passwords are never trimmed, and registration requires at least eight characters.

| Endpoint | Auth | Success | Failure |
| --- | --- | --- | --- |
| `POST /api/auth/register` `{ "email": "...", "password": "..." }` | Public | `201` `{ "user": {"id":"...","email":"..."}, "accessToken":"...", "refreshToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `409 EMAIL_ALREADY_REGISTERED` |
| `POST /api/auth/login` `{ "email": "...", "password": "..." }` | Public | `200` same token and user shape as register | `400 VALIDATION_ERROR`; `401 INVALID_CREDENTIALS` |
| `POST /api/auth/refresh` `{ "refreshToken": "..." }` | Public | `200` `{ "accessToken":"...", "expiresInSeconds":900 }` | `400 VALIDATION_ERROR`; `401 INVALID_REFRESH_TOKEN` |
| `GET /api/auth/me` | Bearer access JWT | `200` `{ "id":"...", "email":"..." }` | `401 UNAUTHORIZED` |

If registration persisted the account but token issuance fails, return `201` `{ "user": {"id":"...","email":"..."}, "authStatus":"LOGIN_REQUIRED" }`. The client offers login rather than retrying registration. Error responses use `{ "error": { "code": "...", "message": "..." } }`. Login uses the same error for unknown email and wrong password.

Access JWTs expire after 15 minutes; refresh JWTs after 30 days. They use separate signing keys from environment configuration and distinct token-type claims. A refresh JWT is never accepted as a Bearer access credential. Refresh returns only a new access JWT and does not rotate the refresh JWT. `GET /api/health` stays public. Heart Rate batch ingestion and all Statistics Heart Rate routes explicitly require Bearer access JWTs and scope reads/writes by the JWT subject. Other metric endpoints and the cross-metric daily dashboard do not yet enforce equivalent per-user access and isolation.

## Current route-level security scope

| Route group | Current access policy |
|---|---|
| `POST /api/auth/register`, `/login`, `/refresh` | Public |
| `GET /api/auth/me` | Bearer access JWT required |
| `GET /api/health` | Public |
| `POST /api/heart-rate/batch` | Bearer access JWT required; account comes from JWT subject |
| `/api/statistics/heart-rate/**` | Bearer access JWT required; reads are scoped to JWT subject |
| Other metric routes and cross-metric dashboard | Do not yet enforce equivalent per-user access and isolation |

Do not infer that a documented endpoint is safe for real multi-user private health data merely because an authentication flow exists. Each metric contract must state its route-level access behavior.
