# VitaLoom Backend

Backend repository for VitaLoom. The application uses Java 21, Spring Boot 4.1.1 and MongoDB.

## Repository guidance

- [`AGENTS.md`](./AGENTS.md) — entry point and non-negotiable instructions for contributors and coding agents.
- [Working Agreement](./docs/agent/working-agreement.md) — how to investigate, implement, retry and verify work.
- [Definition of Done](./docs/agent/definition-of-done.md) — completion criteria and verification expectations.
- [Package Structure](./docs/architecture/package-structure.md) — feature ownership, package layout and dependency direction.
- [Architecture Decisions](./docs/architecture/decisions/README.md) — accepted architectural decisions, their rationale and future implementation direction.
- [Implementation Conventions](./docs/architecture/implementation-conventions.md) — coding, HTTP, service, persistence and testing conventions.

The previous demo implementation remains available in [`vitaloom-demo/demo`](https://github.com/vitaloom/vitaloom-demo/tree/main/demo) as a migration reference. The [API contract index](./docs/api/api-contract.md) links to the authentication, dashboard and metric-specific contracts.

## Development status

The runtime and local MongoDB infrastructure are ready for feature implementation. Authentication, Heart Rate ingestion and statistics, Steps ingestion and statistics, and Water Tracking are implemented. The API contract also describes planned behavior.

The repository is the source of truth for new backend implementation and API-contract changes. Keep Mobile and Data Producer compatibility in mind when changing request/response shapes, statuses, identifiers, timestamps or aggregation behavior.

The local Compose stack starts MongoDB and Mongo Express only. Spring Boot runs on the host. It does not start the demo Nginx gateway, Mobile app or Go Data Producer.

## Prerequisites

- Java 21
- Docker Engine and Docker Compose plugin
- The Maven Wrapper is included; a separate Maven installation is not required.

## 1. Configure the environment

Copy the example file and edit its local credentials.

Linux / macOS:

```bash
cp .env.example .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Change the sample passwords before starting the stack. The checked-in example values are for local development only.

For authentication, set `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET` in the untracked `.env`. Use two different random values of at least 32 bytes each. `JWT_ISSUER` is optional. The application refuses to start without usable signing secrets. Keep these values out of source control and out of the mobile `EXPO_PUBLIC_` configuration.

## 2. Start MongoDB infrastructure

```bash
docker compose up -d
docker compose ps
```

The Compose stack starts:

- **MongoDB 8** as a single-node authenticated replica set named `vitaloom-rs`.
- **Mongo Express** as a local administration UI at [http://localhost:8082](http://localhost:8082) (port 8082 avoids Expo/Metro's default 8081).

The MongoDB root user is for administration. The backend connects with a separate `APP_USER` account that receives only `readWrite` on `APP_DB`.

MongoDB and Mongo Express ports are bound to `127.0.0.1` by default. Run Spring Boot on the host and connect to `localhost:27017`. The Compose stack intentionally does not start a backend container or Nginx gateway.

The replica set provides the MongoDB deployment mode required for multi-document transactions. The Spring transaction manager is disabled by default and can be enabled with `MONGO_TRANSACTIONS_ENABLED=true` when a use case actually requires it.

## 3. Run the backend

Run commands from the repository root so Spring can load the root `.env` file.

Linux / macOS:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

The application listens on port `8080` by default. Override it with `SERVER_PORT` in `.env`.

The auth feature provides `POST /api/auth/register`, `/login`, `/refresh` and protected `GET /api/auth/me`. The protected Water Tracking MVP provides daily and weekly reads plus log creation; see the [Water contract](./docs/features/water/api-contract.md). Heart Rate and Steps batch ingestion and statistics routes are listed in the [API contract index](./docs/api/api-contract.md).

## 4. Run tests

```bash
./mvnw test
```

Windows:

```powershell
.\mvnw.cmd test
```

## MongoDB lifecycle notes

- Initialization scripts run only when the MongoDB data volume is first created.
- Changing credentials in `.env` does not update users in an already initialized volume.
- To recreate the local database and apply new initialization credentials, run `docker compose down -v` and then `docker compose up -d`. **This deletes the local MongoDB data.**
- Do not use the sample credentials or expose this local development stack as a public database service.

## Security scope

Spring Security uses stateless Bearer access JWT authentication for `/api/auth/me`, Heart Rate and Steps batch ingestion and Statistics, and `/api/water/**`. These metric routes scope data to the authenticated account. Registration, login, refresh and health check are public. Sleep routes and the cross-metric daily dashboard do not yet enforce equivalent per-user access and isolation. Refresh JWTs are stateless and have no server-side revocation or rotation in this Basic MVP. Local sign-out clears the mobile credential but cannot invalidate a previously issued refresh JWT. CSRF is disabled for the stateless, non-cookie API; revisit it if cookies are introduced.
