# AGENTS.md

**mqs-pg** — config-driven MQ → PostgreSQL sync (consume MQ events, transform by config,
idempotent MERGE upsert). Two independent apps. CI runs `mvn verify` + `pnpm build`, but there is
still no lint/format/typecheck tooling — don't invent commands for it.

## Read first
- `README.md` — quickstart, mock-MQ walkthrough, and "八个已踩过的坑" (eight silent-failure pitfalls).
- `tech-design.md` — ADRs, correctness proofs, measured results (appendices E/F). Read the relevant ADR before changing pipeline semantics.
- `prd.md` — requirements.
- `docs/optimization-plan.md` — the optimization roadmap (round 1 milestones A–F archived in §3; round 2 milestones G–M in §5) and the `evolution.md` logging convention (§11).
- `evolution.md` — per-change log (background / changes / verification / rollback), newest entry first. Add an entry for every fix.

## Layout
- `backend/` — Spring Boot **4.1.1** / Java **21** / Maven. Root package `com.mqspg`: `config/` (config domain), `mqs/` (SPI + in-process mock), `transform/`, `writer/`, `consumer/`, `retry/`, `raw/`, `console/` (REST). Entry point `MqsPgApplication`.
- `frontend/` — Vue 3 + Vite + Element Plus console (`pnpm`). Vite dev server proxies `/api` → `:8080`.
- `scripts/e2e-config-api.ps1` — end-to-end acceptance; creates and cleans its own route/table/datasource.

## Commands
Backend (run from `backend/`):
```
mvn spring-boot:run                     # requires SPRING_PROFILES_ACTIVE=dev for seed data
mvn test                                # all tests
mvn test "-Dtest=PgWriterTest"          # one class
mvn test "-Dtest=PgWriterTest#method"   # one method
```
- **PowerShell mangles `-Dkey=value` args** (Maven: `Unknown lifecycle phase '...'`). Use environment variables for the profile (`$env:SPRING_PROFILES_ACTIVE='dev'; mvn spring-boot:run`) and quote `-Dtest=...`.

Frontend (run from `frontend/`): `pnpm install`, `pnpm dev` (`:5173`), `pnpm build`. Only these three scripts exist — no `pnpm test` / `pnpm lint`. pnpm 12 blocks dependency postinstall scripts; `pnpm-workspace.yaml` allowlists `esbuild` and `vue-demi` (needed for Vite to start).

## Prerequisites (integration work)
- PostgreSQL **17+** is required (`MERGE ... RETURNING merge_action()`); tested on 18.1. Local default: a docker container named `postgres`, `POSTGRES_PASSWORD=123456`, db `postgres`; override with `MQS_PG_DB_URL` / `MQS_PG_DB_USER` / `MQS_PG_DB_PASSWORD`. Schema `mqs_pg` and all tables are created by Flyway.
- The backend integration tests (`PgWriterTest`, `PipelineIntegrationTest`, `RouteConsumerIntegrationTest`, `RetrySchedulerTest`, `RawRetentionTest`, `ConfigAdminTest`) run against a **Testcontainers** `postgres:18` container (see `AbstractPgIntegrationTest`): only Docker is needed — no pre-started PG and no manual seeding, since Flyway applies `db/migration` + dev `db/demo` on a fresh container. They set `consumer.auto-start=false` so the background consumer does not race the assertions.
- `scripts/e2e-config-api.ps1` needs the backend running. Its SQL access is parameterized: default is `docker exec <DbContainer> psql` (container name defaults to `postgres`, override with `-DbContainer`); use `-SqlMode psql` with `-PgHost/-PgPort/-PgUser/-PgPassword/-PgDatabase` to connect directly instead. The datasource it creates via the API must be reachable **from the backend process**, so point `-TargetJdbcUrl/-TargetUser/-TargetPassword` at it (use the service name when the backend also runs in a container).
- `mqs-pg.mqs.vendor=mock` (default) runs an in-process broker, so the full pipeline works without a real MQ platform.

## Silent-failure gotchas
These do not fail at compile time; they fail at runtime or silently do nothing:
- **Flyway**: Boot 4 modularized autoconfig, so `flyway-core` alone is inert — must use `spring-boot-starter-flyway`, otherwise no tables are created.
- **JDBC URL must include `?currentSchema=mqs_pg,public`** — MyBatis runs unqualified SQL (`FROM cfg_route`) and would otherwise hit `public`.
- **Jackson 2 vs 3**: the Web layer is Jackson 3, but JSONPath/JSLT/JSONB use Jackson 2. Never put a `com.fasterxml.jackson` `JsonNode` in a REST DTO; convert via `JsonNodes#toPlain` first.
- **MyBatis-Plus**: `updateById` skips null fields — write NULL with `UpdateWrapper.set(field, null)`. Conversely, JSONB fields (`content`, `upsert_keys`, `pool_config`) must go through the entity `updateById` so the `JsonbTypeHandler` applies; a raw `JsonNode` in `set()` loses the handler.
- **`MergeSqlBuilder` red line**: only monotonic-guarded `WHEN MATCHED`. Never add an unguarded fallback clause (it would let stale rows overwrite newer ones).
- **Raw message partitions**: create future days ahead to avoid new-day writes landing in the DEFAULT partition; fixing a DEFAULT conflict requires detach → create → move → attach in one transaction (`RawPartitionMaintenance#repairAndCreate`).
- **`/api/mock/publish`**: `body` is a JSON **string**, not a nested object (preserves raw payload exactly).

## Conventions
- Docs, comments and user-facing API messages are **Chinese** — match that in new code.
- Config changes flow through the API: `POST /api/routes/{id}/draft` → publish → activate; do not hand-edit `cfg_version.content` (immutable JSONB snapshots).
- API responses are wrapped `{success, code, message, data}` (`ApiResponse`); the frontend axios layer unwraps to `data`. Controllers live under `/api`.
- Migrations are Flyway under `db/migration` (`V1__init_schema.sql`); `db/demo/V900__demo_seed.sql` is dev-only seed. Add a new versioned migration rather than editing an applied one.
