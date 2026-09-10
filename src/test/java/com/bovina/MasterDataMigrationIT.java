package com.bovina;

import static org.assertj.core.api.Assertions.*;

import com.bovina.platform.application.StableIds;
import com.bovina.support.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MasterDataMigrationIT {
  @Test
  void upgradesTheApprovedIdentitySchemaWithoutRewritingExistingClientFacts() throws Exception {
    var schema = "phase2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var postgres = TestDatabase.POSTGRES;
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION bovina_migration");
      statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO bovina_runtime");
    }
    var baseline =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "bovina_migration", TestDatabase.MIGRATION_PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .target("3")
            .load();
    baseline.migrate();
    assertThat(baseline.info().applied()).hasSize(3);
    var ids = new StableIds();
    var user = ids.next();
    var tenant = ids.next();
    var member = ids.next();
    var client = ids.next();
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
      statement.executeUpdate(
          "INSERT INTO user_account(id,issuer,subject,status,created_at) VALUES ('"
              + user
              + "','https://upgrade.invalid','upgrade','ACTIVE',now())");
      statement.executeUpdate(
          "INSERT INTO organization(id,legal_name,tax_id,timezone,default_locale,status,created_at,created_by) VALUES ('"
              + tenant
              + "','Upgrade tenant','declared','UTC','pt-BR','ACTIVE',now(),'"
              + user
              + "')");
      statement.executeUpdate(
          "INSERT INTO organization_membership(id,organization_id,user_account_id,role,status,valid_from) VALUES ('"
              + member
              + "','"
              + tenant
              + "','"
              + user
              + "','ORG_ADMIN','ACTIVE',now())");
      statement.executeUpdate(
          "INSERT INTO party(id,organization_id,type,display_name,status,occurred_at,origin_type,recorded_by,recorded_at) VALUES ('"
              + client
              + "','"
              + tenant
              + "','PERSON','Original client','ACTIVE',now(),'MANUAL','"
              + user
              + "',now())");
      statement.executeUpdate(
          "INSERT INTO party_role(organization_id,party_id,role) VALUES ('"
              + tenant
              + "','"
              + client
              + "','CLIENT')");
    }
    var upgraded =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), "bovina_migration", TestDatabase.MIGRATION_PASSWORD)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .load();
    assertThat(upgraded.migrate().migrationsExecuted).isGreaterThan(0);
    assertThat(upgraded.info().pending()).isEmpty();
    assertThat(upgraded.validateWithResult().validationSuccessful).isTrue();
    try (var connection = TestDatabase.runtimeConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + schema);
      try (var result =
          statement.executeQuery(
              "SELECT display_name,version,origin_type FROM party WHERE id='" + client + "'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("Original client");
        assertThat(result.getLong(2)).isZero();
        assertThat(result.getString(3)).isEqualTo("MANUAL");
      }
      try (var result =
          statement.executeQuery(
              "SELECT count(*) FROM information_schema.columns WHERE table_schema='"
                  + schema
                  + "' AND column_name='organization_id' AND is_nullable<>'NO'")) {
        result.next();
        assertThat(result.getInt(1)).isZero();
      }
      for (var table :
          java.util.List.of(
              "animal",
              "animal_identifier",
              "animal_ownership_assignment",
              "operational_location",
              "professional_credential",
              "protocol_version",
              "import_batch")) {
        try (var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
          result.next();
          assertThat(result.getInt(1)).isZero();
        }
      }
    }
  }
}
