# Schema migrations

No production schema change is needed in Phase 0. Flyway initializes its history
on an empty PostgreSQL database; Hibernate validates without creating business
tables. Add the first versioned SQL migration only for a real schema change.
Test fixtures live under src/test/resources/database and never ship in the JAR.
