package com.bovina.support.integration;

import com.bovina.platform.application.StableIds;
import com.bovina.support.TrustedTokens;
import java.net.http.HttpClient;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

final class IntegrationTestRuntime implements BeforeAllCallback {
  static final TrustedTokens TOKENS = new TrustedTokens();
  static final StableIds IDS = new StableIds();
  static final HttpClient HTTP = HttpClient.newHttpClient();

  @Override
  public void beforeAll(ExtensionContext context) {
    context
        .getRoot()
        .getStore(ExtensionContext.Namespace.create(IntegrationTestRuntime.class))
        .computeIfAbsent("resources", ignored -> new Resources(), Resources.class);
  }

  private static final class Resources implements AutoCloseable {
    @Override
    public void close() {
      TOKENS.close();
      HTTP.close();
    }
  }
}
