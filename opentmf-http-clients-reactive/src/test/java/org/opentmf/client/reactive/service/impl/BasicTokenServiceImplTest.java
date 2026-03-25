package org.opentmf.client.reactive.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BasicAuthConfig;
import reactor.test.StepVerifier;

class BasicTokenServiceImplTest {

  @Test
  void getTokenType_returnsBasic() {
    var service = new BasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "US-ASCII"));
    assertThat(service.getTokenType()).isEqualTo("Basic");
  }

  @Test
  void getToken_emitsBase64EncodedCredentials() {
    var service = new BasicTokenServiceImpl(
        new BasicAuthConfig("admin", "secret", "US-ASCII"));

    StepVerifier.create(service.getToken())
        .assertNext(token -> {
          String decoded = new String(
              Base64.getDecoder().decode(token), StandardCharsets.US_ASCII);
          assertThat(decoded).isEqualTo("admin:secret");
        })
        .verifyComplete();
  }

  @Test
  void getToken_withAdditionalScopes_returnsSameToken() {
    var service = new BasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "US-ASCII"));

    String plainToken = Objects.requireNonNull(service.getToken().block());
    StepVerifier.create(service.getToken("openid"))
        .expectNext(plainToken)
        .verifyComplete();
  }

  @Test
  void getToken_withUtf8Charset() {
    var service = new BasicTokenServiceImpl(
        new BasicAuthConfig("user", "päss", "UTF-8"));

    StepVerifier.create(service.getToken())
        .assertNext(token -> {
          String decoded = new String(
              Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
          assertThat(decoded).isEqualTo("user:päss");
        })
        .verifyComplete();
  }

  @Test
  void getToken_withInvalidCharset_fallsBackToAscii() {
    var service = new BasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "INVALID_CHARSET"));

    StepVerifier.create(service.getToken())
        .assertNext(token -> {
          String decoded = new String(
              Base64.getDecoder().decode(token), StandardCharsets.US_ASCII);
          assertThat(decoded).isEqualTo("user:pass");
        })
        .verifyComplete();
  }
}
