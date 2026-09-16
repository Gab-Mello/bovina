package com.bovina.platform.application;

public record SearchPage(String query, int page, int size) {
  public SearchPage {
    query = query == null ? "" : query.strip();
    if (query.length() > 200 || page < 0 || page > 10000 || size < 1 || size > 100)
      throw new ApplicationFailure(
          ApplicationFailure.Kind.REJECTED,
          "INVALID_PAGE",
          "Use a search of at most 200 characters, page 0..10000 and size 1..100");
  }

  public int offset() {
    return page * size;
  }

  public String pattern() {
    return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
