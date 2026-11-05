# VitaLoom Backend

Backend repository for VitaLoom. The application uses Java 21, Spring Boot 4.1.1 and MongoDB.

## Repository guidance

- [`AGENTS.md`](./AGENTS.md) — entry point and non-negotiable instructions for contributors and coding agents.
- [Working Agreement](./docs/agent/working-agreement.md) — how to investigate, implement, retry and verify work.
- [Definition of Done](./docs/agent/definition-of-done.md) — completion criteria and verification expectations.
- [Package Structure](./docs/architecture/package-structure.md) — feature ownership, package layout and dependency direction.
- [Implementation Conventions](./docs/architecture/implementation-conventions.md) — coding, HTTP, service, persistence and testing conventions.

The current demo implementation remains available in [`vitaloom-demo/demo`](https://github.com/vitaloom/vitaloom-demo/tree/main/demo). The API behavior baseline remains in [`vitaloom-demo/API_CONTRACT.md`](https://github.com/vitaloom/vitaloom-demo/blob/main/API_CONTRACT.md) until the contract is formally moved here.

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

## 2. Start MongoDB infrastructure

```bash
docker compose up -d
docker compose ps
```

The Compose stack starts:

- **MongoDB 8** as a single-node authenticated replica set named `vitaloom-rs`.
- **Mongo Express** as a local administration UI at [http://localhost:8081](http://localhost:8081).

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

This infrastructure bootstrap does not yet migrate the demo controllers or metric services. API endpoints become available as the feature source is moved into this repository.

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

Spring Security is configured for a stateless API with environment-driven CORS. The current VitaLoom demo has no account, login, role or per-user data-isolation feature, so `/api/**` remains publicly accessible in this baseline. This is not production multi-user authentication.

JWT cookie filters, refresh sessions, password encoding and user-role authorization are intentionally not copied from EngoLearn. Introduce them together with an approved VitaLoom account/authentication feature and its API/data-isolation requirements. CSRF is disabled only for the current stateless, non-cookie API; revisit it before introducing cookie-based authentication.
