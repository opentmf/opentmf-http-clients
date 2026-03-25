package org.opentmf.client.test;

import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.reactive.service.api.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

@SpringBootTest
class ReactiveClientIT {

  @Autowired private ClientProperties firstBasicClientProperties;
  @Autowired private WebClient firstBasicWebClient;
  @Autowired private TokenService firstBasicTokenService;

  @Autowired private ClientProperties secondBasicClientProperties;
  @Autowired private WebClient secondBasicWebClient;
  @Autowired private TokenService secondBasicTokenService;

  @Autowired private ClientProperties healthCheckClientProperties;
  @Autowired private WebClient healthCheckWebClient;
  @Autowired private TokenService healthCheckTokenService;

  @Test
  void testBasicAuthClients_exposeCorrectBeans() {
    Assertions.assertNotNull(firstBasicClientProperties);
    Assertions.assertNotNull(firstBasicWebClient);
    Assertions.assertNotNull(firstBasicTokenService);

    StepVerifier.create(firstBasicTokenService.getToken())
        .expectNextMatches(Objects::nonNull)
        .verifyComplete();

    Assertions.assertNotNull(secondBasicClientProperties);
    Assertions.assertNotNull(secondBasicWebClient);
    Assertions.assertNotNull(secondBasicTokenService);

    StepVerifier.create(secondBasicTokenService.getToken())
        .expectNextMatches(Objects::nonNull)
        .verifyComplete();

    Assertions.assertNotEquals(firstBasicClientProperties, secondBasicClientProperties);
    Assertions.assertNotEquals(firstBasicWebClient, secondBasicWebClient);
  }

  @Test
  void testNoAuthClient_exposeCorrectBeans() {
    Assertions.assertNotNull(healthCheckClientProperties);
    Assertions.assertNotNull(healthCheckWebClient);
    Assertions.assertNotNull(healthCheckTokenService);

    StepVerifier.create(healthCheckTokenService.getToken())
        .expectNextMatches(token -> token != null && token.isEmpty())
        .verifyComplete();
  }
}
