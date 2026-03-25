package org.opentmf.client.reactive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class NoOpTokenServiceTest {

  private final NoOpTokenService service = new NoOpTokenService();

  @Test
  void getTokenType_returnsEmpty() {
    assertThat(service.getTokenType()).isEmpty();
  }

  @Test
  void getToken_emitsEmpty() {
    StepVerifier.create(service.getToken())
        .expectNext("")
        .verifyComplete();
  }

  @Test
  void getToken_withScopes_emitsEmpty() {
    StepVerifier.create(service.getToken("openid profile"))
        .expectNext("")
        .verifyComplete();
  }
}
