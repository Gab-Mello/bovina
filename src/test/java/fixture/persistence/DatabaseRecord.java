package fixture.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "fixture_record", schema = "baseline_probe")
public class DatabaseRecord {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "parent_id", nullable = false)
  private UUID parentId;

  @Column(name = "measured_value", nullable = false)
  private int measuredValue;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "civil_date", nullable = false)
  private LocalDate civilDate;

  @Column(name = "local_time", nullable = false)
  private LocalDateTime localTime;

  @Column(name = "zone_id", nullable = false, length = 80)
  private String zoneId;

  @Version private Long version;

  protected DatabaseRecord() {}

  public DatabaseRecord(UUID id, UUID organizationId, UUID parentId) {
    this.id = id;
    this.organizationId = organizationId;
    this.parentId = parentId;
    this.occurredAt = Instant.parse("2026-09-08T12:34:56.123456Z");
    this.civilDate = LocalDate.of(2026, 9, 8);
    this.localTime = LocalDateTime.of(2026, 9, 8, 9, 34, 56);
    this.zoneId = "America/Sao_Paulo";
  }

  public UUID id() {
    return id;
  }

  public Instant occurredAt() {
    return occurredAt;
  }

  public LocalDate civilDate() {
    return civilDate;
  }

  public LocalDateTime localTime() {
    return localTime;
  }

  public String zoneId() {
    return zoneId;
  }

  public Long version() {
    return version;
  }

  public void changeMeasuredValue(int value) {
    if (value < 0) throw new IllegalArgumentException("Measurement cannot be negative");
    measuredValue = value;
  }
}
