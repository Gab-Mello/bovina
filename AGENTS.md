# Backend integration tests

When adding or changing backend integration tests, follow these repository conventions:

- Name classes, packages, methods, data, and helpers for durable product capabilities or technical invariants. Do not use delivery milestones, tickets, or commit history as test vocabulary.
- Put tests beside the relevant bounded context under `src/test/java/com/bovina`; use `platform` for cross-cutting database, migration, and runtime guarantees. Keep test support under `src/test`.
- Prefer a plain JUnit unit test for pure domain logic. Choose the narrowest real integration boundary that proves the guarantee; use full HTTP/Security/Spring/JPA/PostgreSQL only when those boundaries matter.
- Use PostgreSQL Testcontainers for constraints, grants, SQL, Flyway, Hibernate mappings, locking, and transaction semantics. Do not substitute H2 or mocks for database guarantees.
- Write one cohesive scenario per test with visible Arrange/Act/Assert flow. Assert observable behavior and durable facts, including negative outcomes and absence of duplicate facts where relevant; avoid incidental internal call sequences and brittle whole-JSON assertions.
- Keep tests independent of execution order and shared mutable business data. Use explicit dates and scenario-defining values; prefer small composable fixtures with important preconditions visible, not generic builders, deep inheritance, or a custom test framework.
- Exercise real commits, rollbacks, retries, and separate transactions when testing transaction behavior. Do not wrap such tests in a test-level transaction that masks production semantics.
- Coordinate concurrency with barriers, latches, controlled locks, and post-race invariant assertions. Never depend on arbitrary sleeps or suite-level parallel execution to reproduce a race.
- Use direct SQL only when the database guarantee itself needs proof. Do not duplicate production algorithms in expected-value calculations; specify explicit known cases instead.
- Reuse compatible Spring contexts and the shared `TestDatabase` container safely; avoid unnecessary profiles, property overrides, `@DirtiesContext`, or mutable global scenario fixtures.
- Use thin HTTP and domain fixture helpers only where they remove meaningful repetition. Keep payloads and scenario-defining facts visible in the test.
- Run focused checks while iterating and the complete Maven `clean verify` gate once the change is ready. A commit boundary alone is not a reason to rerun tests.
