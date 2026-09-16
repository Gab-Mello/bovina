package com.bovina.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.bovina.support.TestDatabase;
import com.bovina.support.fixture.*;
import com.bovina.support.integration.OperationalDistributionTest;
import jakarta.persistence.Entity;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.utility.MountableFile;

class CommercialRecoveryIT extends OperationalDistributionTest {
  @Test
  void restoredBusinessBackupValidatesSchemaLineageHistoryReplayAndRuntimePrivileges()
      throws Exception {
    var tenant = tenant("Recovery Lab");
    var fresh = ProductionFixtures.freshEmbryos(api, tenant, 1);
    var recipient = ProductionFixtures.animal(api, tenant.id(), "FEMALE", "Recovery Recipient");
    var cycle = TransferFixtures.openCycle(api, tenant.id(), recipient, LocalDate.of(2020, 1, 1));
    var reservation =
        TransferFixtures.reservation(api, tenant.id(), fresh.embryos().getFirst(), cycle, 0);
    var transfer =
        TransferFixtures.perform(
            api,
            tenant.id(),
            reservation.id(),
            1,
            fresh.professional(),
            Instant.parse("2020-01-02T12:00:00Z"));
    var check = id();
    var checkBatch = id();
    var checkCommand =
        TransferFixtures.checkBatch(
            checkBatch,
            TransferFixtures.checkItem(
                check,
                transfer.id(),
                Instant.parse("2020-02-01T12:00:00Z"),
                "PREGNANT",
                fresh.professional(),
                null,
                null));
    assertStatus(api.post(tenant.id(), "/pregnancy-checks:bulk", checkBatch, checkCommand), 200);
    assertStatus(api.post(tenant.id(), "/pregnancy-checks:bulk", checkBatch, checkCommand), 200);
    var stock = DistributionFixtures.stock(api, tenant);
    var expectedLineage =
        json.readTree(
            api.get(tenant.id(), "/embryos/" + fresh.embryos().getFirst() + "/traceability")
                .body());
    var document = id();
    var version = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/documents",
            document,
            Map.of("id", document, "typeCode", "OPERATIONAL_EVIDENCE")),
        201);
    var bytes = "Recovery evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertStatus(
        api.putDocumentVersion(
            tenant.id(), document, version, 0, "evidence.txt", "text/plain", bytes),
        201);
    var destination = DistributionFixtures.recipient(api, tenant);
    var shipment = id();
    var shipmentItem = DistributionFixtures.item(stock);
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments",
            shipment,
            DistributionFixtures.preparation(
                shipment,
                destination,
                stock,
                List.of(shipmentItem),
                List.of(
                    Map.of(
                        "id",
                        id(),
                        "typeCode",
                        "SOURCE_EVIDENCE",
                        "documentId",
                        document,
                        "versionId",
                        version)))),
        201);
    var dispatch =
        api.post(
            tenant.id(), "/shipments/" + shipment + ":dispatch", DistributionFixtures.dispatch());
    assertStatus(dispatch, 200);
    assertThat(
            json.readTree(dispatch.body())
                .path("dispatchEvidence")
                .path("complianceResult")
                .asString())
        .isEqualTo("UNKNOWN");
    var returnKey = id();
    assertStatus(
        api.post(
            tenant.id(),
            "/shipments/" + shipment + ":return",
            returnKey,
            Map.of(
                "movementId",
                returnKey,
                "itemId",
                shipmentItem.get("id"),
                "locationId",
                stock.location(),
                "expectedShipmentVersion",
                1,
                "expectedPackageVersion",
                4,
                "occurredAt",
                Instant.parse("2026-09-04T12:00:00Z"),
                "reason",
                "Observed return to original custody")),
        200);
    var beforeAudits =
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE organization_id=?", Long.class, tenant.id());
    var beforeReceipts =
        jdbc.queryForObject(
            "SELECT count(*) FROM idempotent_command WHERE organization_id=?",
            Long.class,
            tenant.id());

    var postgres = TestDatabase.POSTGRES;
    var name = "recovery_" + id().toString().replace("-", "");
    var dump = "/tmp/" + name + ".dump";
    postgres.copyFileToContainer(
        MountableFile.forHostPath("scripts/postgres-backup.sh"), "/tmp/postgres-backup.sh");
    postgres.copyFileToContainer(
        MountableFile.forHostPath("scripts/postgres-restore.sh"), "/tmp/postgres-restore.sh");
    succeeded(
        postgres.execInContainer(
            "env",
            "PGHOST=127.0.0.1",
            "PGPORT=5432",
            "PGDATABASE=" + postgres.getDatabaseName(),
            "PGUSER=" + postgres.getUsername(),
            "PGPASSWORD=" + postgres.getPassword(),
            "bash",
            "/tmp/postgres-backup.sh",
            dump));
    succeeded(
        postgres.execInContainer(
            "createdb", "-U", postgres.getUsername(), "--owner=bovina_migration", name));
    try {
      succeeded(
          postgres.execInContainer(
              "env",
              "PGHOST=127.0.0.1",
              "PGPORT=5432",
              "PGDATABASE=" + name,
              "PGUSER=" + postgres.getUsername(),
              "PGPASSWORD=" + postgres.getPassword(),
              "bash",
              "/tmp/postgres-restore.sh",
              dump));
      var refused =
          postgres.execInContainer(
              "env",
              "PGHOST=127.0.0.1",
              "PGPORT=5432",
              "PGDATABASE=" + name,
              "PGUSER=" + postgres.getUsername(),
              "PGPASSWORD=" + postgres.getPassword(),
              "bash",
              "/tmp/postgres-restore.sh",
              dump);
      assertThat(refused.getExitCode())
          .as("A restore must never overwrite a populated database")
          .isEqualTo(2);
      var url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
      var flyway =
          Flyway.configure()
              .dataSource(url, "bovina_migration", TestDatabase.MIGRATION_PASSWORD)
              .locations("classpath:db/migration")
              .load();
      assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
      assertThat(flyway.migrate().migrationsExecuted).isZero();
      validateMappings(url);
      var restored =
          new JdbcTemplate(
              new DriverManagerDataSource(url, "bovina_runtime", TestDatabase.RUNTIME_PASSWORD));
      var queries =
          new com.bovina.traceability.infrastructure.TraceabilityQueries(
              new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(restored));
      var restoredLineage =
          json.readTree(
              json.writeValueAsString(queries.lineage(tenant.id(), fresh.embryos().getFirst())));
      assertThat(restoredLineage).isEqualTo(expectedLineage);
      assertThat(
              restored.queryForObject(
                  "SELECT count(*) FROM pregnancy_check WHERE organization_id=? AND transfer_id=?",
                  Integer.class,
                  tenant.id(),
                  transfer.id()))
          .isEqualTo(1);
      assertThat(
              restored.queryForObject(
                  "SELECT result FROM pregnancy_check WHERE organization_id=? AND id=?",
                  String.class,
                  tenant.id(),
                  check))
          .isEqualTo("PREGNANT");
      assertThat(
              restored.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE organization_id=?",
                  Long.class,
                  tenant.id()))
          .isEqualTo(beforeAudits);
      assertThat(
              restored.queryForObject(
                  "SELECT count(*) FROM idempotent_command WHERE organization_id=?",
                  Long.class,
                  tenant.id()))
          .isEqualTo(beforeReceipts);
      var persistedIntent =
          restored.queryForMap(
              "SELECT command_type,request_hash FROM idempotent_command WHERE organization_id=? AND idempotency_key=?",
              tenant.id(),
              checkBatch);
      var replayStore = new com.bovina.platform.infrastructure.CommandReceiptStore(restored, json);
      var replay =
          replayStore.replay(
              tenant.id(),
              checkBatch,
              (String) persistedIntent.get("command_type"),
              (String) persistedIntent.get("request_hash"),
              com.bovina.transfer.domain.PregnancyCheckBatch.Result.class);
      assertThat(replay.items()).hasSize(1);
      assertThat(replay.items().getFirst().checkId()).isEqualTo(check);
      assertThat(
              restored.queryForObject(
                  "SELECT content FROM document_blob WHERE organization_id=? AND version_id=?",
                  byte[].class,
                  tenant.id(),
                  version))
          .isEqualTo(bytes);
      assertThat(
              restored.queryForObject(
                  "SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='flyway_schema_history'",
                  String.class))
          .isEqualTo("bovina_migration");
      assertThat(
              restored.queryForObject(
                  """
          SELECT count(*) FROM oocyte_collection c WHERE c.organization_id=? AND (
            c.viable>c.total_recovered OR c.viable<0 OR (SELECT coalesce(sum(m.allocated_oocytes),0)
              FROM mating m WHERE m.organization_id=c.organization_id AND m.oocyte_collection_id=c.id AND m.status<>'CANCELLED')>c.viable)
          """,
                  Integer.class,
                  tenant.id()))
          .isZero();
      assertThat(
              restored.queryForObject(
                  """
          SELECT count(*) FROM embryo_package p LEFT JOIN LATERAL (
            SELECT sequence,to_location_id FROM inventory_movement m
            WHERE m.organization_id=p.organization_id AND m.package_id=p.id ORDER BY sequence DESC LIMIT 1
          ) last ON true WHERE p.organization_id=? AND (p.current_location_id IS DISTINCT FROM last.to_location_id
            OR p.last_movement_sequence<>coalesce(last.sequence,0))
          """,
                  Integer.class,
                  tenant.id()))
          .isZero();
      var ledger =
          restored.queryForList(
              "SELECT sequence,from_location_id,to_location_id FROM inventory_movement WHERE organization_id=? AND package_id=? ORDER BY sequence",
              tenant.id(),
              stock.packaged().packageId());
      assertThat(ledger).hasSize(3);
      java.util.UUID replayLocation = null;
      long replaySequence = 0;
      for (var movement : ledger) {
        assertThat(movement.get("sequence")).isEqualTo(++replaySequence);
        assertThat(movement.get("from_location_id")).isEqualTo(replayLocation);
        replayLocation = (UUID) movement.get("to_location_id");
      }
      assertThat(replayLocation).isEqualTo(stock.location());
      assertThat(
              restored.queryForObject(
                  "SELECT current_location_id FROM embryo_package WHERE organization_id=? AND id=?",
                  UUID.class,
                  tenant.id(),
                  stock.packaged().packageId()))
          .isEqualTo(replayLocation);
      assertThat(
              restored.queryForObject(
                  "SELECT availability_status FROM embryo WHERE organization_id=? AND id=?",
                  String.class,
                  tenant.id(),
                  stock.production().embryos().getFirst()))
          .isEqualTo("SHIPPED_OUT");
      assertThat(
              restored.queryForObject(
                  "SELECT recipient_name FROM shipment_destination_snapshot WHERE organization_id=? AND shipment_id=?",
                  String.class,
                  tenant.id(),
                  shipment))
          .isEqualTo("Destination Lab");
      try (var connection =
          DriverManager.getConnection(url, "bovina_runtime", TestDatabase.RUNTIME_PASSWORD)) {
        for (var table :
            List.of(
                "audit_event",
                "inventory_movement",
                "embryo_transfer",
                "pregnancy_check",
                "document_version",
                "document_blob")) {
          try (var statement =
              connection.prepareStatement(
                  "SELECT has_table_privilege(current_user,?,'UPDATE'),has_table_privilege(current_user,?,'DELETE')")) {
            statement.setString(1, table);
            statement.setString(2, table);
            try (var result = statement.executeQuery()) {
              assertThat(result.next()).isTrue();
              assertThat(result.getBoolean(1)).as(table + " is append-only").isFalse();
              assertThat(result.getBoolean(2)).isFalse();
            }
          }
        }
      }
    } finally {
      succeeded(postgres.execInContainer("dropdb", "-U", postgres.getUsername(), name));
    }
  }

  private void validateMappings(String url) throws Exception {
    var mappings =
        new Configuration()
            .setPhysicalNamingStrategy(
                new org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl())
            .setProperty("hibernate.connection.url", url)
            .setProperty("hibernate.connection.username", "bovina_runtime")
            .setProperty("hibernate.connection.password", TestDatabase.RUNTIME_PASSWORD)
            .setProperty("hibernate.hbm2ddl.auto", "validate")
            .setProperty("hibernate.jdbc.time_zone", "UTC");
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
    for (var entity : scanner.findCandidateComponents("com.bovina"))
      mappings.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
    try (var sessions = mappings.buildSessionFactory()) {
      assertThat(sessions.isOpen()).isTrue();
    }
  }

  private static void succeeded(Container.ExecResult result) {
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }
}
