package org.opentmf.client.bearer.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class BearerTokenServiceMockImplTest {

  private final BearerTokenServiceMockImpl service = new BearerTokenServiceMockImpl();

  @Test
  void getTokenType_returnsBearer() {
    assertThat(service.getTokenType()).isEqualTo("Bearer");
  }

  @Test
  void getToken_emitsFixedJwt() {
    StepVerifier.create(service.getToken())
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_withScopes_emitsFixedJwt() {
    StepVerifier.create(service.getToken("openid"))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_withUri_emitsFixedJwt() {
    StepVerifier.create(service.getToken(URI.create("https://auth.example.com"), "openid"))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_withEnricher_emitsFixedJwt() {
    StepVerifier.create(service.getToken(Map.of("key", "value")))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void clearCache_doesNotThrow() {
    assertThatCode(service::clearCache).doesNotThrowAnyException();
  }
}
