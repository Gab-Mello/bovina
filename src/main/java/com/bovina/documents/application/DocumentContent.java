package com.bovina.documents.application;

import java.util.UUID;

/** Byte storage boundary; document identity, metadata and authorization stay outside this port. */
public interface DocumentContent {
  void put(UUID organizationId, UUID versionId, byte[] content);

  byte[] get(UUID organizationId, UUID versionId);
}
