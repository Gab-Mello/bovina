package com.bovina.embryology.domain;

import com.bovina.platform.application.*;
import java.time.*;
import java.util.*;

public final class AssessmentCatalog {
  private AssessmentCatalog() {}

  public record Scheme(UUID id, String code, String name, String status) {
    public Scheme {
      StableIds.requireVersion7(id);
      code = AssessmentCatalog.code(code, 80);
      name = text(name, 200);
      status = status == null ? "ACTIVE" : status;
      if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) invalid();
    }
  }

  public record Version(
      UUID id,
      UUID schemeId,
      String versionLabel,
      LocalDate effectiveFrom,
      String status,
      UUID publishedBy,
      Instant publishedAt,
      List<Code> codes) {
    public Version {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(schemeId);
      versionLabel = text(versionLabel, 80);
      status = status == null ? "PUBLISHED" : status;
      if (!status.equals("PUBLISHED") && !status.equals("RETIRED")) invalid();
      Objects.requireNonNull(publishedBy);
      Objects.requireNonNull(publishedAt);
      if (codes == null || codes.isEmpty() || codes.size() > 200) invalid();
      codes = List.copyOf(codes);
      var identities = new HashSet<String>();
      for (var item : codes) {
        if (!item.schemeVersionId().equals(id)
            || !identities.add(item.dimension() + ":" + item.code())) invalid();
      }
    }
  }

  public record Code(
      UUID id,
      UUID schemeVersionId,
      Dimension dimension,
      String code,
      String displayName,
      int sortOrder) {
    public Code {
      StableIds.requireVersion7(id);
      StableIds.requireVersion7(schemeVersionId);
      Objects.requireNonNull(dimension);
      code = AssessmentCatalog.code(code, 80);
      displayName = text(displayName, 200);
    }
  }

  public enum Dimension {
    DEVELOPMENT_STAGE,
    QUALITY_GRADE,
    OTHER
  }

  private static String code(String value, int max) {
    value = text(value, max).toUpperCase(Locale.ROOT);
    if (!value.matches("[A-Z][A-Z0-9_.-]{0," + (max - 1) + "}")) invalid();
    return value;
  }

  private static String text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) invalid();
    return value.strip();
  }

  private static void invalid() {
    throw new ApplicationFailure(
        ApplicationFailure.Kind.REJECTED,
        "INVALID_ASSESSMENT_CATALOG",
        "Assessment scheme, version or code is invalid");
  }
}
