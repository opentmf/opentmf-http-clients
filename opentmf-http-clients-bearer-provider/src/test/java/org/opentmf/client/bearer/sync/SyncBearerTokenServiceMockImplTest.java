package org.opentmf.client.bearer.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SyncBearerTokenServiceMockImplTest {

  private final SyncBearerTokenServiceMockImpl service = new SyncBearerTokenServiceMockImpl();

  @Test
  void getTokenType_returnsBearer() {
    assertThat(service.getTokenType()).isEqualTo("Bearer");
  }

  @Test
  void getToken_returnsFixedJwt() {
    assertThat(service.getToken()).startsWith("eyJ");
  }

  @Test
  void getToken_withScopes_returnsFixedJwt() {
    assertThat(service.getToken("openid")).startsWith("eyJ");
  }

  @Test
  void clearCache_doesNotThrow() {
    service.clearCache();
  }
}
