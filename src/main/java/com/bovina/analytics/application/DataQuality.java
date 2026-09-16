package com.bovina.analytics.application;

import com.bovina.analytics.infrastructure.DataQualityQueries;
import com.bovina.identity.application.TenantAccess;
import com.bovina.platform.application.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataQuality {
  private final TenantAccess access;
  private final DataQualityQueries queries;

  public DataQuality(TenantAccess access, DataQualityQueries queries) {
    this.access = access;
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public PageResult<Issue> search(ExecutionContext context, SearchPage page) {
    access.require(context, "embryology:read");
    access.require(context, "inventory:read");
    return new PageResult<>(queries.search(context.tenantId(), page), page.page(), page.size());
  }

  public record Issue(String subjectType, UUID subjectId, String code, String severity) {}
}
