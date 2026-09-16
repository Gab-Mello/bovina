# BovInA backend engineering guidelines

This repository contains the production backend for BovInA.

BovInA is a commercial MVP intended for real production use.

This is not a study project, tutorial, proof of concept, or architecture exercise.

“MVP” means:

- keep the product focused;
- avoid speculative infrastructure;
- avoid unnecessary abstractions;
- deliver the smallest useful production-quality solution.

“MVP” does NOT justify compromising:

- correctness;
- security;
- tenant isolation;
- data integrity;
- historical traceability;
- transactional guarantees;
- concurrency safety;
- maintainability;
- testability;
- production-safe configuration;
- operational reliability.

The engineering target is:

> simple enough for a small team to understand and operate, but robust enough to safely run in production.

Prefer boring, explicit, maintainable solutions over clever or fashionable architecture.

---

# Sources of truth

Before changing domain behavior or architecture, read the relevant parts of:

- `PIVE_DOMAIN_MODEL_3.md`
  - canonical product/domain model;
  - domain terminology;
  - invariants;
  - canonical lineage;
  - aggregate boundaries;
  - historical semantics.

- `PIVE_IMPLEMENTATION_PLAN.md`
  - approved engineering interpretation;
  - persistence strategy;
  - concurrency expectations;
  - architectural decisions;
  - production/runtime expectations.

- this `AGENTS.md`
  - repository-level engineering conventions.

The current implementation is not automatically correct if it conflicts with the canonical domain model.

Do not preserve legacy behavior merely because it already exists.

Do not invent behavior when the domain or regulatory requirement is explicitly unresolved.

---

# Production engineering standard

Write every change assuming it may reach production.

Before accepting a solution, ask:

- Can this create incorrect business facts?
- Can it corrupt or silently rewrite historical information?
- Can one tenant observe or affect another tenant?
- Can concurrent requests violate the invariant?
- Can retry duplicate business facts or side effects?
- Can a partial failure leave impossible state?
- Can an invalid configuration start successfully?
- Can this fail safely?
- Can another engineer understand this code six months from now?
- Is the implementation simpler than the problem, or have we created unnecessary machinery?

Production quality does not mean maximum architecture.

It means correctness, clarity, safety, predictability, and maintainability.

---

# Architecture

The backend is a modular monolith organized by bounded context.

Prefer:

- cohesive packages by product/domain capability;
- thin controllers;
- application services for orchestration;
- transaction boundaries in the application layer;
- domain objects for local invariants and meaningful behavior;
- infrastructure adapters for persistence and technical concerns;
- dedicated query/read services where aggregate loading is not appropriate.

Normal dependency direction is approximately:

`api -> application -> domain`

Infrastructure implements useful technical boundaries where they actually exist.

Do not enforce architectural purity for its own sake.

In particular:

- JPA annotations on domain entities are acceptable;
- do not create separate domain/persistence models solely for purity;
- do not create interfaces without a meaningful boundary or variation;
- do not create `Service` + `ServiceImpl` pairs by convention;
- do not add generic repository abstractions over Spring Data without a real need;
- do not introduce command buses, event buses, workflow engines, state-machine frameworks, or generic CRUD infrastructure without concrete justification.

Avoid circular dependencies between bounded contexts.

Domain code must not depend on:

- Spring MVC;
- servlet APIs;
- HTTP;
- controllers;
- transport DTOs;
- security framework implementation details.

---

# MVP and overengineering

This is production software, but still an MVP.

Do not introduce infrastructure such as:

- Kafka;
- Redis;
- Elasticsearch;
- microservices;
- event sourcing;
- distributed sagas;
- generic rule engines;
- generic workflow engines;
- generic offline-sync frameworks;

unless an existing requirement cannot reasonably be satisfied by the modular monolith and PostgreSQL architecture.

Prefer synchronous local transactions when an operation belongs to the same application/database boundary.

Do not build abstractions for hypothetical future requirements.

Do not create extension points merely because they might be useful someday.

---

# Code quality

Code quality is a production concern.

Optimize for:

- clarity;
- cohesion;
- explicitness;
- predictable behavior;
- small conceptual surface area;
- easy review;
- easy debugging;
- safe modification.

A future engineer should be able to understand why the code exists and what invariant it protects without reconstructing the entire project history.

## Responsibilities

Keep classes and methods focused on a coherent responsibility.

Avoid:

- god services;
- god entities;
- controllers containing business workflows;
- repositories implementing domain policy;
- utility classes accumulating unrelated behavior;
- classes that exist only because a design pattern suggested they should.

Do not split code mechanically into tiny classes if doing so makes the workflow harder to follow.

Cohesion matters more than line count.

## Naming

Use domain terminology consistently.

Names should describe business meaning rather than technical accident.

Prefer:

- `reserveEmbryo`
- `recordPregnancyCheck`
- `dispatchShipment`
- `placeInventoryHold`

over vague verbs such as:

- `process`
- `handle`
- `execute`
- `updateStatus`

when a stronger domain verb exists.

Avoid names tied to:

- tickets;
- implementation phases;
- temporary migrations;
- commit history.

## Methods

Prefer methods with a clear purpose and understandable control flow.

Avoid:

- excessive nesting;
- long boolean expressions with unclear meaning;
- hidden side effects;
- multiple unrelated responsibilities;
- large parameter lists that indicate missing concepts;
- methods that mutate distant state unexpectedly.

Extract logic when doing so improves understanding or prevents duplication.

Do not extract one-line methods merely to reduce line count.

## Duplication

Do not duplicate domain policy.

If the same business rule appears in multiple modules or services, determine where that rule actually belongs.

Duplicated mapping or trivial plumbing is sometimes preferable to a premature generic abstraction.

Distinguish:

- harmful duplication of business rules;
- harmless repetition of simple technical code.

## Abstractions

Every abstraction should earn its existence.

Before introducing one, ask:

- Does it isolate a real technical boundary?
- Does it remove meaningful duplication?
- Does it represent a real domain concept?
- Does it improve testability in a meaningful way?
- Is more than one behavior/implementation actually expected?

Avoid abstractions whose only benefit is theoretical flexibility.

Prefer concrete code until variation is real.

---

# Java quality

Write idiomatic Java 21.

Prefer:

- constructor injection;
- immutable values;
- records for immutable DTO/value-like structures when appropriate;
- explicit domain methods;
- final local state where useful;
- clear control flow;
- standard library solutions.

Avoid:

- mutable static state;
- reflection-heavy infrastructure;
- excessive inheritance;
- unsafe shared collections;
- unnecessary synchronization;
- streams that make domain logic harder to understand;
- clever functional chains when ordinary code is clearer.

## Nullability

Be explicit about optionality.

Do not use `null` where absence has meaningful domain semantics and can be modeled clearly.

Do not wrap every value in `Optional`.

`Optional` is primarily useful as a return type for genuine absence, not as a universal field/parameter type.

Validate required values close to the appropriate boundary.

## Exceptions

Exceptions must preserve useful semantics.

Avoid:

- `catch (Exception)` without a strong reason;
- swallowing exceptions;
- converting unrelated failures into generic business conflicts;
- using exceptions for ordinary branching;
- leaking persistence or framework exceptions through the public API.

Translate errors at the appropriate boundary.

Preserve the original cause for diagnostics where appropriate.

Do not expose stack traces or internal SQL to clients.

## Collections

Do not expose mutable internal collections unnecessarily.

Avoid returning internal collections that allow aggregate state to be mutated outside controlled domain methods.

Use defensive copies/unmodifiable views when appropriate.

## Time

Use explicit time semantics.

Prefer `Instant`, `OffsetDateTime`, `LocalDate`, etc. according to the real domain meaning.

Do not use server-local timezone implicitly for business facts.

Tests should use explicit timestamps instead of `now()` where deterministic time matters.

## UUIDs

Use the repository's established UUID strategy.

Do not derive business meaning from UUID ordering unless the design explicitly relies on that behavior.

Human-readable/business codes are separate from technical IDs.

---

# Domain correctness

Favor facts over mutable counters.

Historically meaningful facts must not be silently rewritten.

Use the appropriate mechanism:

- normal update for mutable non-historical data;
- deactivate/archive for referenced master data;
- append-only facts for historical observations/events;
- correction for historically significant changes;
- snapshot for externally issued/reproducible evidence;
- derived projection for current/read-optimized state.

Do not create mutable denormalized fields that become competing sources of truth.

Canonical lineage is represented through references between facts.

Do not create:

- giant aggregates containing the entire lineage;
- duplicated lineage tables that become another authority.

---

# Stable identity and historical truth

Important business facts need stable identity.

Historical records must remain interpretable after master data changes.

Do not silently:

- replace historical relationships;
- null historical foreign keys;
- overwrite issued evidence;
- delete performed facts;
- rebuild historical output using current mutable master data when a snapshot is required.

Audit logs do not replace proper historical modeling.

Audit, correction, inventory movement, document version, and business fact are distinct concepts.

---

# Domain state

Keep independent domain dimensions independent.

For embryos, concepts such as:

- availability;
- preservation;
- hold;
- physical location;

must not be collapsed into a single generic status.

Physical inventory authority belongs to the inventory movement ledger.

Do not treat:

- shipment status;
- embryo availability;
- package metadata;
- current-location projection;

as competing physical inventory authorities.

Read models must never become hidden sources of truth.

---

# Tenant isolation

This is a multi-tenant SaaS.

Tenant isolation is both a security invariant and a data-integrity invariant.

Authority must come from trusted authentication/membership context.

Never trust an arbitrary organization identifier from a client as authorization.

Prefer tenant-scoped repository/query operations.

Be especially careful with:

- `findById`;
- native queries;
- joins;
- bulk commands;
- imports;
- documents;
- audit;
- recall;
- read models;
- metrics;
- administrative operations.

Where appropriate, reinforce tenant ownership with compound foreign keys and database constraints.

Cross-tenant resource access should generally appear as not found instead of revealing resource existence.

Never weaken tenant isolation for implementation convenience.

---

# Authentication

Authentication is JWT-based and provider-neutral.

Do not couple domain/application logic to a specific identity provider without an explicit requirement.

Production authentication must validate the expected JWT contract.

Review carefully:

- issuer;
- signature;
- accepted algorithms;
- expiration;
- not-before;
- relevant audience semantics;
- claim mapping.

Test/dev authentication shortcuts must never become production fallbacks.

---

# Authorization

Authorization must be consistently enforced.

Check permissions for:

- writes;
- reads;
- administration;
- documents;
- audit;
- metrics;
- operational endpoints.

Do not assume controller annotations are sufficient if the application use case can be invoked through another path.

Avoid scattered ad-hoc role checks when a shared permission model already exists.

---

# Transactions

Application services own meaningful business transactions.

A transaction should correspond to required business atomicity.

Do not create a transaction merely around each repository call.

If an operation must succeed or fail as one business action, its durable database changes should normally belong to the same transaction.

Examples include operations such as:

- allocation;
- transfer execution;
- inventory movement;
- dispatch;
- reconciliation;

according to their domain boundaries.

Do not perform slow external network operations while holding database locks unless explicitly necessary.

Do not pretend PostgreSQL and an external system participate in one atomic transaction.

---

# Concurrency

Concurrency-sensitive workflows require explicit design.

Do not assume requests will execute sequentially.

Use the appropriate combination of:

- database constraints;
- optimistic locking;
- pessimistic locking;
- deterministic lock ordering.

Known sensitive areas include:

- idempotency claims;
- imports;
- oocyte allocation;
- mating allocation;
- embryo reservations;
- transfer execution;
- inventory movements;
- inventory holds;
- thaw;
- reconciliation;
- shipment reservation;
- dispatch;
- correction decisions;
- document version creation.

For multi-resource locking, use deterministic order where applicable.

Concurrency correctness must be expressed in durable state, not only response ordering.

---

# Idempotency

Retry-safe commands must preserve semantic idempotency.

Expected behavior:

same key + same semantic request  
→ replay the same successful result

same key + different semantic request  
→ conflict

concurrent equivalent requests  
→ one durable business result

failed contender  
→ no partial durable state

Do not classify arbitrary database failures as successful replay.

Do not introduce unbounded retries or spin loops.

Retries should have a clear reason and bounded behavior.

---

# PostgreSQL

PostgreSQL is part of the correctness model.

Do not substitute H2 or mocked behavior for guarantees that depend on PostgreSQL.

Use database constraints when they provide reliable local enforcement:

- `NOT NULL`;
- foreign keys;
- tenant-aware compound foreign keys;
- `UNIQUE`;
- partial unique indexes;
- `CHECK`.

Application validation does not replace database integrity.

Conversely, do not encode complex cross-aggregate policy into obscure database mechanisms when application orchestration is clearer.

---

# Flyway migrations

Never rewrite an applied migration merely to clean up history.

Schema changes require a new migration.

Migrations should be:

- deterministic;
- reviewable;
- safe for the expected deployment process;
- compatible with PostgreSQL production semantics.

Consider:

- locks;
- existing rows;
- defaults;
- NOT NULL transitions;
- index creation;
- data transformations;
- runtime compatibility.

Do not assume an empty database is the only migration scenario once production exists.

---

# SQL

Be careful with:

- native SQL;
- `ON CONFLICT`;
- partial indexes;
- lock queries;
- `SELECT FOR UPDATE`;
- grants;
- append-only protections.

Use parameterized queries.

Never concatenate untrusted input into SQL.

A broad:

`ON CONFLICT DO NOTHING`

is only appropriate when every uniqueness conflict it can suppress genuinely has the intended semantic meaning.

Otherwise target the exact constraint/conflict.

---

# JPA and Hibernate

Keep Open Session in View disabled.

Prefer lazy associations unless eager behavior is deliberately required.

Avoid:

- accidental eager graphs;
- uncontrolled bidirectional relationships;
- broad `CascadeType.ALL`;
- accidental orphan deletion;
- exposing entities through the API;
- serializing lazy proxies.

Define cascades according to true aggregate ownership.

Do not use Lombok `@Data` indiscriminately on JPA entities.

Avoid unrestricted:

- setters;
- generated `equals/hashCode`;
- generated `toString`;

on entities where those semantics are unsafe.

Use explicit domain mutation methods for critical state.

---

# Query behavior

Operational queries should be bounded and predictable.

Use:

- pagination;
- deterministic ordering;
- explicit projections;
- query services/read models;

when loading aggregates is not appropriate.

Watch for:

- N+1;
- query-per-item loops;
- unbounded `findAll`;
- full tenant scans;
- huge entity graphs;
- loading document blobs when only metadata is required.

Do not solve query problems with cache infrastructure before proving a need.

---

# APIs

Do not expose JPA entities directly.

Use explicit request/response DTOs.

Prefer explicit domain commands over arbitrary mutation endpoints when lifecycle semantics matter.

Prefer operations such as:

- reserve;
- release;
- perform;
- correct;
- thaw;
- dispatch;
- return;
- cancel;

instead of generic mutation of status fields.

Use Bean Validation at transport boundaries.

Domain/database invariants must still be enforced where appropriate.

Use structured Problem Details consistently.

Do not leak:

- stack traces;
- SQL;
- internal class names unnecessarily;
- storage paths/keys;
- secrets;
- internal security details.

---

# Pagination and filtering

All potentially large collections must be bounded.

Use deterministic ordering.

If client-controlled sorting exists, only allow known supported fields.

When using SQL `LIKE`/`ILIKE`, treat user-provided wildcard characters literally unless wildcard search is explicitly part of the API contract.

Do not allow arbitrary SQL fragments through sort/filter inputs.

---

# Bulk operations

Bulk commands must have explicit semantics.

Be clear whether a bulk operation is:

- fully atomic;
- atomic per group;
- partial with item-level results.

Do not accidentally produce partial success simply because processing happened in a loop.

Use bounded request sizes.

Avoid loading enormous batches into memory.

---

# Compliance and regulation

Compliance behavior must remain contextual and versioned.

Do not invent universal regulatory requirements.

Do not assume:

- every shipment requires GTA;
- one disease requirement applies everywhere;
- possession of a document proves compliance.

Preserve:

- `UNKNOWN`;
- `NOT_APPLICABLE`;

where appropriate.

Unresolved regulatory requirements should remain explicit blockers instead of guessed rules.

---

# File/document handling

Document versions that represent historical evidence must be immutable.

Validate:

- authorization;
- tenant ownership;
- size limits;
- hashes;
- storage consistency.

Do not leak internal storage paths.

Do not load large document contents unnecessarily when only metadata is required.

A PostgreSQL-backed blob adapter is acceptable for the MVP while protected by a storage abstraction.

Do not introduce object storage solely for architectural purity.

---

# Configuration

Configuration is production code.

Treat configuration mistakes as potential production defects.

Prefer typed `@ConfigurationProperties` for meaningful groups of settings.

Validate dangerous configuration at startup.

Production must fail clearly when a required secure value is absent.

Do not provide insecure fallback values for:

- passwords;
- JWT configuration;
- credentials;
- sensitive endpoints.

Test/dev defaults must not silently apply in production.

---

# Database runtime configuration

Runtime database credentials should not own the schema or require superuser privileges.

Migration and runtime privileges should remain separate.

Production expectations include:

- PostgreSQL;
- Flyway;
- Hibernate `validate`;
- no `ddl-auto=update`;
- bounded connection pool;
- explicit credentials;
- production-safe timeouts where justified.

Do not enable verbose SQL logging in production by default.

---

# Security configuration

Review changes affecting:

- CORS;
- CSRF;
- session creation;
- authentication filters;
- Actuator;
- OpenAPI;
- forwarded headers;
- reverse-proxy assumptions;
- management endpoints.

Do not weaken production security merely to simplify local development.

Local development convenience should be isolated to development configuration.

---

# Secrets

Never commit secrets.

Never log secrets.

Never bake secrets into container images.

Use environment/runtime secret injection.

Do not provide known default production passwords.

---

# Docker and runtime

The runtime image should be minimal, predictable, and production-safe.

Prefer:

- multi-stage builds when useful;
- non-root execution;
- immutable application artifacts;
- clear startup failure;
- correct signal handling;
- graceful shutdown.

Do not include build tools in the runtime image without need.

Do not install packages “just in case”.

Do not bake configuration/secrets into the image.

---

# Health checks

Liveness and readiness have different semantics.

Liveness should indicate whether the process needs restart.

Readiness should indicate whether the service can safely serve requests.

Do not use a readiness dependency as liveness if that would create restart loops during temporary external outages.

Health checks should be lightweight.

---

# Graceful shutdown

Production shutdown must allow normal in-flight work to finish within a bounded period.

Do not accept new traffic after readiness is removed.

Do not introduce background work that ignores shutdown semantics.

---

# Logging

Logs should help diagnose production problems.

Use meaningful context such as:

- correlation/request IDs;
- operation type;
- safe resource context;
- exception stack traces for unexpected errors.

Do not log:

- JWTs;
- passwords;
- secrets;
- document contents;
- unnecessarily sensitive personal/business data.

Avoid noisy logs inside hot loops.

Avoid duplicate stack traces across multiple layers for the same failure.

---

# Observability

Use Spring Boot / Actuator / Micrometer where they are sufficient.

Metrics should describe operational behavior without high-cardinality identifiers.

Avoid labels such as:

- organization ID;
- user ID;
- animal ID;
- embryo ID;
- package ID;
- arbitrary request IDs.

Useful signals may include:

- command latency;
- failures;
- conflicts;
- idempotent replay;
- audit failures;
- database pool behavior;
- readiness.

Detailed production SLO thresholds, dashboards, external alerting, and escalation routes are separate operational work unless explicitly requested.

---

# Backup and restore

Backup code/scripts are production-critical.

Use strict failure handling.

Be careful with:

- quoting;
- credentials;
- temporary files;
- permissions;
- destination selection;
- overwrite behavior;
- PostgreSQL version compatibility.

A backup is not considered reliable merely because it was created.

Restore must be tested.

After restore, the system should still pass applicable:

- migrations;
- schema validation;
- authorization/grants expectations;
- important data invariants;
- inventory ledger replay.

---

# Performance

Do not optimize without evidence.

Investigate structural risks first:

- unbounded queries;
- N+1;
- query loops;
- large object graphs;
- excessive serialization;
- large transactions;
- excessive locking;
- missing indexes on measured critical paths;
- O(n²) logic on potentially meaningful datasets.

Measure before adding:

- caches;
- materialized views;
- Redis;
- Elasticsearch;
- asynchronous infrastructure.

Indexes should correspond to actual query patterns.

---

# Comments and JavaDoc

Do not add comments that merely narrate obvious code.

Avoid AI-style comments such as:

```java
// Create the client
Client client = new Client(...);

// Save the client
repository.save(client);