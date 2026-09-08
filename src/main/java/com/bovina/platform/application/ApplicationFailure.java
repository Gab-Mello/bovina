package com.bovina.platform.application;

import java.util.Objects;

public final class ApplicationFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    NOT_FOUND,
    CONFLICT,
    REJECTED
  }

  private final String code;
  private final Kind kind;

  public ApplicationFailure(Kind kind, String code, String safeDetail) {
    super(Objects.requireNonNull(safeDetail));
    this.kind = Objects.requireNonNull(kind);
    if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,99}")) {
      throw new IllegalArgumentException("A stable error code is required");
    }
    this.code = code;
  }

  public String code() {
    return code;
  }

  public Kind kind() {
    return kind;
  }
}
