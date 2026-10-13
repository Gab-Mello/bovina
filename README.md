# BovInA Backend

![BovInA](https://i.imgur.com/1UzEy1n.png)

![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)

BovInA is a backend for bovine in vitro embryo production (PIVE), currently
being evolved into a commercial MVP with a strong focus on security, data
integrity, traceability, and maintainability.

## Overview

The current backend supports animal and farm records, OPU and oocyte collection,
semen provenance and fertilization, embryo production and evaluation,
cryostorage and inventory, transfer and pregnancy follow-up, distribution and
traceability, and document, audit, and contextual compliance foundations.

## Architecture and tech stack

The backend is a **modular monolith** organized by domain capability. Modules
use `api`, `application`, `domain`, and `infrastructure` packages where those
responsibilities exist; PostgreSQL is the relational source of truth.

The core stack is Java 21, Spring Boot 4.1, Spring Security, Spring Data JPA /
Hibernate, PostgreSQL 18, Flyway, OpenAPI/Swagger, Maven, Docker Compose, and
PostgreSQL Testcontainers for integration tests.

## Security and multi-tenancy

The API validates JWTs, resolves user membership and RBAC permissions, and
isolates data by organization. Tenant-scoped requests select the organization
with `X-Organization-ID`; the header alone does not grant access.

## Local backend for frontend development

This Compose setup is for **local development only**. It runs PostgreSQL, the API, and a
mock OAuth2/OIDC issuer; the API still validates signed JWTs and enforces real
membership, RBAC, and tenant isolation.

### Start

Docker with the Compose v2 plugin and curl are required. From the repository root:

```bash
cp .env.example .env
# Set three distinct local passwords in .env:
# POSTGRES_PASSWORD, BOVINA_MIGRATION_PASSWORD, BOVINA_RUNTIME_PASSWORD
docker compose up --build
```

`.env.example` selects `compose.dev.yaml` through `COMPOSE_FILE`; the base
`compose.yaml` does not start the local OAuth mock. If you already have an
older `.env`, add `COMPOSE_FILE=compose.yaml:compose.dev.yaml` to it.

The default local URLs are:

- API readiness: http://localhost:8080/actuator/health/readiness
- Swagger UI: http://localhost:8080/swagger-ui.html
- Local token issuer: http://localhost:8090/default

The API and mock issuer are published only on host loopback.

### Frontend quick start

```bash
JWT="$(./scripts/dev-token)"
```

This stores the token in your current shell. Copy its value into the
frontend's local development authentication configuration or state.

On a fresh database, bootstrap the first organization once using the request
below and save its `organizationId`. For normal tenant-scoped requests from
`http://localhost:5173` or `http://localhost:3000`, the frontend needs
`API_BASE_URL=http://localhost:8080` and these headers:

```http
Authorization: Bearer <JWT>
X-Organization-ID: <organizationId>
```

In Swagger's **Authorize** dialog, paste only the JWT value (`eyJ...`), **not**
`Bearer eyJ...`; its HTTP bearer scheme adds the prefix. The local token expires
after about one hour; run `./scripts/dev-token` again to renew it. Its local
`client_credentials` grant is a development convenience, not the SPA login
flow. Real browser login will use Authorization Code + PKCE with a real
identity provider.

Swagger is anonymous only under the `dev` profile. `/api/**` remains
authenticated, and a token alone does not grant membership or tenant access.

### Bootstrap the first organization

On a fresh local database, use the token above to create the first organization:

```bash
curl --fail-with-body -X POST http://localhost:8080/api/v1/bootstrap/organizations \
  -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "0199da2a-6e00-7000-8000-000000000001",
    "legalName": "Local Development Lab",
    "taxId": "LOCAL-001",
    "timezone": "America/Sao_Paulo"
  }'
```

The response's `id` is the `organizationId`. The API, not the mock issuer, creates
`UserAccount` and the `ORG_ADMIN` membership for this bootstrap identity.
Use a **fresh UUIDv7** for each new organization; the sample ID is suitable
only for a first run. Relevant client-generated command/resource IDs throughout
the API also require UUIDv7. Browser `crypto.randomUUID()` generates UUIDv4
and is not suitable for those fields. Send `Idempotency-Key` for commands
whose OpenAPI contract requires it.

To discover organizations later:

```bash
curl --fail-with-body http://localhost:8080/api/v1/me/memberships \
  -H "Authorization: Bearer $JWT"
```

For tenant-scoped requests, select an organization explicitly:

```bash
curl --fail-with-body http://localhost:8080/api/v1/me \
  -H "Authorization: Bearer $JWT" \
  -H 'X-Organization-ID: <organizationId>'
```

Local CORS does not enable browser credentials or wildcard origins.

### Stop, reset, and troubleshoot

```bash
docker compose down
```

This stops the stack **without deleting** PostgreSQL data. To erase local data
and bootstrap again from an empty database:

```bash
docker compose down -v
```

Changing PostgreSQL passwords in `.env` after the volume was initialized
does **not** recreate database users. Keep the original passwords or reset the
local volume if its data can be discarded. A missing `POSTGRES_PASSWORD` (or
either application DB password) means `.env` has not been filled in. If a
token is rejected, verify that the mock is healthy, obtain a fresh token, and
check that its issuer matches the local settings. If ports are occupied,
adjust `API_PORT`/`POSTGRES_PORT` or stop the conflicting process.

Production deployments must not opt into `compose.dev.yaml`. The `prod`
profile disables Swagger by default, rejects local issuer/JWKS/CORS settings,
and leaves bootstrap disabled unless explicitly configured for a trusted
identity. The mock issuer and `dev-token` helper are not part of the API image.

## Project background and evolution

BovInA began as a course completion project (TCC), developed in collaboration
with a colleague and presented at **MOSTRATEC**, where it received **3rd place
in the Computer Science category**. That original project provided the
foundation for this repository. The backend was subsequently extensively
refactored and evolved into a more mature, domain-oriented implementation
being prepared as a commercial MVP, not merely the original academic prototype.
