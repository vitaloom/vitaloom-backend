# VitaLoom Backend

Backend repository for VitaLoom. The application uses Java 21, Spring Boot 4.1.1 and MongoDB.

## Repository guidance

- [Documentation index](./docs/README.md) — task-oriented map to the API, architecture, feature and agent documents.
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

## 2. Start local infrastructure

```powershell
docker network inspect vitaloom-assistant-vector --format '{{.Name}}' 2>$null
if ($LASTEXITCODE -ne 0) { docker network create vitaloom-assistant-vector }
docker compose -p vitaloom-vector -f docker-compose.assistant-vector.yaml up -d --wait
docker compose up -d --wait
docker compose ps
```

Create the network once. The main project uses it to check VectorDB readiness. Nginx starts when the probe can reach a writable Atlas Local primary and list search indexes. If an earlier start left the probe unhealthy, finish starting the vector project, wait for the probe to show `healthy` in `docker compose ps assistant-vector-health`, then retry the main startup.

If upgrading from a combined stack, stop and remove the old `assistant-vector` container before starting the new project. To locate it, run `docker ps -a --filter label=com.docker.compose.service=assistant-vector` and select the container from the old app project. Run `docker stop <old-container>` if it is running, then `docker rm <old-container>` to release its name and port while keeping the named volumes. The new Compose reuses the three `vitaloom-backend_assistant_vector_*` volumes by default. For a previous custom project name, set `ASSISTANT_VECTOR_VOLUME_PREFIX` in `.env` to that name. Use [the Assistant bootstrap](./docs/api/assistant-course.md) to provision the search index and seed the FAQ fixture.

The main Compose stack starts:

- **MongoDB 8** as a single-node authenticated replica set named `vitaloom-rs`.
- **Mongo Express** as a local administration UI at [http://localhost:8082](http://localhost:8082) (port 8082 avoids Expo/Metro's default 8081).
- **Nginx** as an API gateway on host port `80` by default. Set `NGINX_PORT` in `.env` to change the gateway port.

The MongoDB root user is for administration. The backend connects with a separate `APP_USER` account that receives only `readWrite` on `APP_DB`.

MongoDB and Mongo Express ports are bound to `127.0.0.1` by default. Run Spring Boot on the host as described below. Nginx forwards requests to Spring Boot using `host.docker.internal` and the `SERVER_PORT` from `.env`.

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

After changing `SERVER_PORT`, restart Spring Boot and run `docker compose up -d nginx` to apply the new upstream port. With the default gateway port, `GET http://localhost/api/health` checks connectivity through Nginx.

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
- To recreate the local database and apply new initialization credentials, run `docker compose down -v` and then `docker compose up -d`. **This deletes the app MongoDB data.** The VectorDB data, configuration and search index volumes belong to the separate `vitaloom-vector` project.
- Do not use the sample credentials or expose this local development stack as a public database service.

## Security scope

Spring Security uses stateless Bearer access JWT authentication for `/api/auth/me`, Heart Rate and Steps batch ingestion and Statistics, `/api/water/**`, and Sleep Journal `/api/sleep/v2/**`. These metric routes scope records to the authenticated account. Registration, login, refresh and health check are public. The cross-metric daily dashboard remains outside that implementation. Refresh JWTs are stateless and have no server-side revocation or rotation in this Basic MVP. Local sign-out clears the mobile credential but cannot invalidate a previously issued refresh JWT. CSRF is disabled for the stateless, non-cookie API; revisit it if cookies are introduced.
