package org.opentmf.client.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoOpSyncTokenServiceTest {

  private final NoOpSyncTokenService service = new NoOpSyncTokenService();

  @Test
  void getTokenType_returnsEmpty() {
    assertThat(service.getTokenType()).isEmpty();
  }

  @Test
  void getToken_returnsEmpty() {
    assertThat(service.getToken()).isEmpty();
  }

  @Test
  void getToken_withScopes_returnsEmpty() {
    assertThat(service.getToken("openid profile")).isEmpty();
  }
}
