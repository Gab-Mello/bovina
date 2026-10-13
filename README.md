# BovInA Backend

![BovInA](https://i.imgur.com/1UzEy1n.png)

![Java](https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge\&logo=openjdk\&logoColor=white)
![Spring](https://img.shields.io/badge/spring-%236DB33F.svg?style=for-the-badge\&logo=spring\&logoColor=white)
![MySQL](https://img.shields.io/badge/mysql-4479A1.svg?style=for-the-badge\&logo=mysql\&logoColor=white)
![AWS](https://img.shields.io/badge/AWS-%23FF9900.svg?style=for-the-badge\&logo=amazon-aws\&logoColor=white)
![Swagger](https://img.shields.io/badge/-Swagger-%23Clojure?style=for-the-badge\&logo=swagger\&logoColor=white)

## Project overview

**BovInA** is a digital platform developed as part of my course completion project (TCC), in collaboration with a colleague.

The project was presented at **MOSTRATEC**, the largest science and technology fair in Latin America, where it won **3rd place in the Computer Science category**.

This repository contains the **backend** of the system, which was the part I was responsible for developing.

The platform focuses on digitizing and optimizing the management of **In Vitro Embryo Production (PIVE)** in cattle. By replacing manual processes with a centralized digital system, BovInA improves the organization, monitoring, and analysis of important stages of the PIVE process, such as oocyte collection, in vitro fertilization, embryo production, embryo transfer, and pregnancy tracking.

The goal is to provide a more effective tool for managing bovine embryo production, contributing to genetic improvement and reproductive efficiency in the livestock sector.

## Main features

* **Animal management**

  * Registration and tracking of donors, receivers, and bulls.

* **Oocyte collection**

  * Recording collections and monitoring total and viable oocytes.

* **Embryo production**

  * Tracking viable embryo production and production efficiency.

* **Embryo transfer**

  * Managing embryo transfer data and related production outcomes.

* **Pregnancy tracking**

  * Monitoring pregnancy results from embryo transfers.

* **Reports and advanced filters**

  * Efficiency in viable embryo production by animal.
  * Average number of viable oocytes collected per donor.
  * Best bull and donor combinations.
  * Production and pregnancy success indicators.

## Tech stack

* **Java 21**
* **Spring Boot**
* **Spring Web**
* **Spring Data JPA / Hibernate**
* **MySQL**
* **Swagger / OpenAPI**
* **Docker / Docker Compose**
* **AWS EC2 / RDS**

## System architecture

![System Architecture](https://i.imgur.com/FYP72wJ.png)

The system follows a layered backend architecture:

1. **Frontend**

   * Built with HTML, CSS, JavaScript, React, and React Native.
   * Provides interfaces for data input, visualization, and analysis.
   * Communicates with the backend through REST APIs.

2. **Backend**

   * Built with Java and Spring Boot.
   * Exposes REST endpoints for the frontend.
   * Handles validation, business rules, persistence, and reporting.

3. **Database**

   * MySQL database used to persist animals, collections, productions, transfers, pregnancies, schedules, and related data.

### Backend layers

* **Controllers**

  * Receive HTTP requests and expose the API endpoints.

* **Services**

  * Contain business logic and coordinate application flows.

* **Repositories**

  * Handle database access through Spring Data JPA.

* **Entities**

  * Represent the persistence model mapped to the database.

* **DTOs**

  * Represent request and response objects used by the API.

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

The API and mock issuer are published only on host loopback. The API container
fetches public signing keys at `http://dev-auth:8080/default/jwks` on the
Compose network; `localhost` inside that container is **not** the host.

### Obtain a development token

```bash
JWT="$(./scripts/dev-token)"
```

The helper prints a signed access token valid for about one hour, with
`iss=http://localhost:8090/default`, `sub=dev-operator`, and `aud=bovina`.
Paste it into Swagger's **Authorize** dialog or send it as
`Authorization: Bearer <JWT>`. Run the helper again after expiration or
recreating the mock container. The helper uses the mock's
`client_credentials` endpoint only as a **local token-issuance convenience**;
it is not a user-login design for the SPA. Real browser login will use
Authorization Code + PKCE with a real identity provider.

Swagger is anonymous only under the `dev` profile. `/api/**` remains
authenticated, and a token alone does not grant membership or tenant access.

### Bootstrap the first organization

On a fresh local database, issue a token and create the first organization:

```bash
JWT="$(./scripts/dev-token)"
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

A frontend normally needs only `API_BASE_URL=http://localhost:8080`, the
short-lived JWT, and `ORGANIZATION_ID`. CORS allows the exact local origins
`http://localhost:5173` and `http://localhost:3000`; it does not enable
browser credentials or wildcard origins.

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

## Refactoring note

This codebase is currently being refactored to improve code quality, maintainability, consistency, and alignment with modern Java and Spring Boot practices.

The refactor focuses on improvements such as:

* Cleaner service boundaries.
* Better transaction management.
* More consistent error handling.
* Safer validation.
* Improved DTO usage.
* Reduced coupling between API contracts and persistence entities.
* Better configuration practices.
* Improved Docker-based local development.

The goal of the refactor is to improve the internal quality of the backend while preserving the same core business features of the original BovInA system.

## Project status

BovInA is an academic project originally developed for a TCC and science fair presentation. It remains a valuable backend project for studying Java, Spring Boot, REST APIs, JPA/Hibernate, MySQL, API documentation, Docker, and real-world refactoring practices.
