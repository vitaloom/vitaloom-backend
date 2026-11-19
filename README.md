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

Nginx defines rate-limit zones in `nginx/rate-limit-zones.conf` and applies them in `nginx/rate-limit-routes.conf`. After changing either file, recreate the gateway with `docker compose up -d --no-deps --force-recreate nginx`.

For an isolated backend benchmark with several simulated users on one IP, set `INGESTION_LIMIT_DRY_RUN=on` in the test Compose environment before recreating Nginx. The default is `off`, which enforces both ingestion limits.

The auth feature provides `POST /api/auth/register`, `/login`, `/refresh` and protected `GET /api/auth/me`. The protected Water Tracking MVP provides daily and weekly reads plus log creation; see the [Water contract](./docs/features/water/api-contract.md). Heart Rate and Steps batch ingestion and statistics routes are listed in the [API contract index](./docs/api/api-contract.md).

## 4. Run tests

```bash
./mvnw test
```

Windows:

```powershell
.\mvnw.cmd test
```

## Local observability

On Windows, start the database and the separate monitoring stack, then run Spring Boot with the observability profile:

```powershell
docker compose up -d mongodb nginx
docker compose -f docker-compose.obs.yml up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=observability"
```

On macOS or Linux, use `./mvnw` in the last command. If the wrapper is unavailable on Windows, use `mvn.cmd spring-boot:run "-Dspring-boot.run.profiles=observability"` with an installed Maven. The regular backend launch leaves OTLP export disabled.

Spring Boot pushes cumulative metrics every 15 seconds to the Collector on localhost port 4318. Prometheus scrapes the Collector over the Compose network. Local ports can be overridden with `OBS_OTLP_PORT`, `OBS_PROMETHEUS_PORT` and `OBS_GRAFANA_PORT`. If changing `OBS_OTLP_PORT`, set it in the environment of both Compose and Spring Boot.

- Open Grafana at http://127.0.0.1:3000 and select the **VitaLoom Backend** dashboard. A fresh local Grafana volume uses the default `admin` login and prompts for a password change.
- Open Prometheus at http://127.0.0.1:9090. Send requests to `GET /api/health` and check `http_server_requests_milliseconds_count{application="vitaloom-backend"}` and `http_server_requests_milliseconds_bucket{application="vitaloom-backend"}` after an export and scrape cycle. Rate and percentile panels need at least two samples.
- `up{job="otel-collector"}` shows whether Prometheus can scrape the Collector. The dashboard also checks for a backend process metric, which expires after 45 seconds without updates, plus time for the next scrape to observe its absence.

Stop the monitoring stack with `docker compose -f docker-compose.obs.yml down`. The Prometheus and Grafana volumes persist.

### Docker container metrics

The observability Compose stack starts cAdvisor automatically. Open **VitaLoom Infrastructure** in Grafana and select a container by name to see CPU, memory working set, and network receive and transmit rates. The Collector scrapes cAdvisor over the internal Compose network and keeps metrics for the `vitaloom-backend` and `vitaloom-observability` projects. Rate panels need at least two scrapes.

Start the backend Compose stack first so it creates the `vitaloom-network` and the Nginx monitoring volumes used by the Collector. Open **VitaLoom Nginx** in Grafana to see HTTP request rate, current connections by state, rate-limit rejections, and accepted and handled connection rates. Nginx serves `stub_status` on an unpublished container port. The request counter includes the Collector's own status requests, including when there is no application traffic.

Docker rotates the Nginx container logs at 10 MB per file and keeps up to three files. Recreate the Nginx container to apply logging changes to an existing container.

The separate monitoring file records requests rejected by the Nginx rate limiter and a zero-value heartbeat for each internal status scrape. The Collector reads this volume with read-only access and publishes the rejection counter to Prometheus. The Nginx container checks the file size every five seconds, rotates at 10 MiB, and retains two rotated files. A burst can take the active file over the threshold between checks.

The Collector stores its read position in a separate volume. Its in-memory rejection counter resets on restart, and an extended outage can miss entries if their files rotate out before collection. To compare gateway activity with backend traffic, run Spring Boot with the `observability` profile and view **VitaLoom Backend** over the same time range.

### MongoDB service metrics

On a new or existing MongoDB volume, create a dedicated monitoring account once. After MongoDB is healthy, open its shell as the local administrator:

```powershell
docker compose exec mongodb sh -c 'exec mongosh --username "$MONGO_INITDB_ROOT_USERNAME" --authenticationDatabase admin --password'
```

In `mongosh`, run the following and enter a new monitoring password when prompted:

```javascript
db.getSiblingDB("admin").createUser({
  user: "vitaloom_monitor",
  pwd: passwordPrompt(),
  roles: [{ role: "clusterMonitor", db: "admin" }]
})
```

Set `OBS_MONGODB_PASSWORD` to that password in your local `.env`. Set `OBS_MONGODB_USER` if you chose a different username. Restart the Collector with `docker compose -f docker-compose.obs.yml up -d --no-deps otel-collector` and open **VitaLoom MongoDB**. The Collector connects directly to the single local replica-set node over Docker and collects service metrics every 30 seconds. The dashboard shows current and available connections and operations per second by type. The operation counters apply to the MongoDB server and include traffic from clients other than VitaLoom Backend.

### Windows host metrics

Download the [windows_exporter executable](https://github.com/prometheus-community/windows_exporter/releases/tag/v0.32.1) and run it directly on Windows during measurement. For the x64 executable downloaded to the Downloads folder:

```powershell
& "$env:USERPROFILE\Downloads\windows_exporter-0.32.1-amd64.exe" --web.listen-address=127.0.0.1:9182 --collectors.enabled=cpu,memory,logical_disk,physical_disk,net
```

Leave the window open while measuring and press Ctrl+C to stop. The Collector reads metrics every 15 seconds through `host.docker.internal:9182`. Open **VitaLoom Windows Host** for host CPU, available RAM, free disk space, disk latency, and network throughput. Use the network adapter selector to focus on the interface carrying application traffic. If the exporter is stopped or unreachable, the dashboard shows that the Windows metrics source is down.

On Docker Desktop with Linux containers, cAdvisor uses the Linux PID namespace, the Docker socket for container names, and the containerd socket for container metadata with the containerd image store. The sockets grant access to their respective APIs. Only cAdvisor mounts them, and its metrics port stays inside Compose.


## MongoDB lifecycle notes

- Initialization scripts run only when the MongoDB data volume is first created.
- Changing credentials in `.env` does not update users in an already initialized volume.
- To recreate the local database and apply new initialization credentials, run `docker compose down -v` and then `docker compose up -d`. **This deletes the app MongoDB data.** The VectorDB data, configuration and search index volumes belong to the separate `vitaloom-vector` project.
- Do not use the sample credentials or expose this local development stack as a public database service.

## Security scope

Spring Security uses stateless Bearer access JWT authentication for `/api/auth/me`, Heart Rate and Steps batch ingestion and Statistics, `/api/water/**`, and Sleep Journal `/api/sleep/v2/**`. These metric routes scope records to the authenticated account. Registration, login, refresh and health check are public. The cross-metric daily dashboard remains outside that implementation. Refresh JWTs are stateless and have no server-side revocation or rotation in this Basic MVP. Local sign-out clears the mobile credential but cannot invalidate a previously issued refresh JWT. CSRF is disabled for the stateless, non-cookie API; revisit it if cookies are introduced.
