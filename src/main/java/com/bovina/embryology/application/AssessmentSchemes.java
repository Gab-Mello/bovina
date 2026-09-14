package com.bovina.embryology.application;

import com.bovina.audit.application.*;
import com.bovina.embryology.domain.AssessmentCatalog.*;
import com.bovina.embryology.infrastructure.AssessmentCatalogStore;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssessmentSchemes {
  private final TenantAccess access;
  private final AssessmentCatalogStore store;
  private final CommandReceipts receipts;
  private final AuditRecorder audit;
  private final StableIds ids;
  private final Clock clock;

  public AssessmentSchemes(
      TenantAccess access,
      AssessmentCatalogStore store,
      CommandReceipts receipts,
      AuditRecorder audit,
      StableIds ids,
      Clock clock) {
    this.access = access;
    this.store = store;
    this.receipts = receipts;
    this.audit = audit;
    this.ids = ids;
    this.clock = clock;
  }

  @Transactional
  public Scheme register(ExecutionContext c, UUID key, Scheme input) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "REGISTER_ASSESSMENT_SCHEME_V1",
        input,
        Scheme.class,
        () -> {
          store.insertScheme(c.tenantId(), input);
          record(c, "ASSESSMENT_SCHEME", input.id());
          return input;
        });
  }

  @Transactional
  public Version publish(ExecutionContext c, UUID key, Publish input) {
    access.require(c, "embryology:write");
    return receipts.replayOrExecute(
        c,
        key,
        "PUBLISH_ASSESSMENT_VERSION_V1",
        input,
        Version.class,
        () -> {
          var scheme =
              store.scheme(c.tenantId(), input.schemeId()).orElseThrow(AssessmentSchemes::missing);
          if (!scheme.status().equals("ACTIVE"))
            throw new ApplicationFailure(
                ApplicationFailure.Kind.CONFLICT,
                "ASSESSMENT_SCHEME_INACTIVE",
                "Assessment scheme is inactive");
          var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
          var codes =
              input.codes().stream()
                  .map(
                      code ->
                          new Code(
                              code.id(),
                              input.id(),
                              code.dimension(),
                              code.code(),
                              code.displayName(),
                              code.sortOrder()))
                  .toList();
          var version =
              new Version(
                  input.id(),
                  input.schemeId(),
                  input.versionLabel(),
                  input.effectiveFrom(),
                  "PUBLISHED",
                  c.actorId(),
                  now,
                  codes);
          store.insertVersion(c.tenantId(), version);
          record(c, "ASSESSMENT_SCHEME_VERSION", version.id());
          return version;
        });
  }

  @Transactional(readOnly = true)
  public Version getVersion(ExecutionContext c, UUID id) {
    access.require(c, "embryology:read");
    return requireVersion(c.tenantId(), id);
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public Version requirePublished(UUID tenant, UUID id) {
    var version = store.version(tenant, id, true).orElseThrow(AssessmentSchemes::missing);
    if (!version.status().equals("PUBLISHED"))
      throw new ApplicationFailure(
          ApplicationFailure.Kind.CONFLICT,
          "ASSESSMENT_VERSION_RETIRED",
          "Assessment version is retired");
    return version;
  }

  public Map<UUID, Code> requireCodes(UUID tenant, UUID version, Collection<UUID> ids) {
    var codes = store.codes(tenant, version, ids);
    if (codes.size() != new HashSet<>(ids).size())
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "ASSESSMENT_CODE_NOT_FOUND",
          "Assessment code does not belong to the selected version");
    return codes;
  }

  private Version requireVersion(UUID tenant, UUID id) {
    return store.version(tenant, id, false).orElseThrow(AssessmentSchemes::missing);
  }

  private void record(ExecutionContext c, String type, UUID id) {
    audit.record(
        new AuditEvent(
            ids.next(), c, clock.instant(), "REGISTER", type, id, 0L, null, null, "PUBLISHED"));
  }

  private static ApplicationFailure missing() {
    return new ApplicationFailure(
        ApplicationFailure.Kind.NOT_FOUND,
        "ASSESSMENT_VERSION_NOT_FOUND",
        "Assessment scheme or version not found");
  }

  public record Publish(
      UUID id, UUID schemeId, String versionLabel, LocalDate effectiveFrom, List<CodeInput> codes) {
    public Publish {
      if (codes == null) codes = List.of();
      else codes = List.copyOf(codes);
    }
  }

  public record CodeInput(
      UUID id, Dimension dimension, String code, String displayName, int sortOrder) {}
}
