# VitaLoom Backend

Backend repository for VitaLoom. The intended application stack is Java 21, Spring Boot and MongoDB. The backend application source is being moved from the local demo workspace; until that migration is completed, the current implementation reference remains [`vitaloom-demo/demo`](https://github.com/vitaloom/vitaloom-demo/tree/main/demo).

## Repository guidance

- [`AGENTS.md`](./AGENTS.md) — entry point and non-negotiable instructions for contributors and coding agents.
- [Working Agreement](./docs/agent/working-agreement.md) — how to investigate, implement, retry and verify work.
- [Definition of Done](./docs/agent/definition-of-done.md) — completion criteria and verification expectations.
- [Package Structure](./docs/architecture/package-structure.md) — feature ownership, package layout and dependency direction.
- [Implementation Conventions](./docs/architecture/implementation-conventions.md) — coding, HTTP, service, persistence and testing conventions.

The package structure is the target for new code and code migrated into this repository; it does not authorize unrelated refactoring of the demo workspace. The current API behavior baseline is documented in [`vitaloom-demo/API_CONTRACT.md`](https://github.com/vitaloom/vitaloom-demo/blob/main/API_CONTRACT.md) and will become the contract source in this repository in a later step.

## Local infrastructure

The Docker Compose stack currently provides an authenticated, single-node MongoDB replica set and Mongo Express for local development. The backend application, Nginx gateway and their Docker build are intentionally not included yet; they will be added when the verified application source is migrated from `vitaloom-demo`.

### Prerequisites

- Docker Engine or Docker Desktop with the Compose plugin.

### Start MongoDB

Create the local environment file:

```bash
cp .env.example .env
```

Edit `.env` and replace the example passwords before starting the services. Then run:

```bash
docker compose up -d
docker compose ps
docker compose logs -f mongodb
```

MongoDB is exposed at `localhost:27017`. Mongo Express is available at [http://localhost:8081](http://localhost:8081) and protected by the credentials in `.env`.

To stop the services while preserving database files:

```bash
docker compose down
```

To reset the local database completely (this deletes the named MongoDB volumes):

```bash
docker compose down -v
```

### MongoDB access model

- The root account (`MONGO_USER` / `MONGO_PASS`) is used for MongoDB administration and initialization.
- The application account (`APP_USER` / `APP_PASSWORD`) receives only `readWrite` on `APP_DB`.
- MongoDB runs as a single-node replica set named by `MONGO_REPLICA_SET`, with internal keyfile authentication enabled.
- The keyfile is generated locally inside the MongoDB config volume on first startup; it is not committed to Git.
- The initialization script runs only when MongoDB initializes an empty data directory. Changing credentials in `.env` does not automatically update users in an existing volume.

This setup provides database authentication and least-privilege application access. It does **not** add application-user authentication, authorization, or per-user data isolation. Do not expose this local development stack directly to the public internet.

## Scope

This repository owns backend behavior, persistence and the API contract consumed by the separate VitaLoom Mobile and Data Producer repositories. Keep cross-repository compatibility in mind when changing endpoint behavior, payloads, identifiers, timestamps or aggregation rules.

The current demo is not a production multi-user health-data service. Authentication and user-level data isolation have not been implemented.
