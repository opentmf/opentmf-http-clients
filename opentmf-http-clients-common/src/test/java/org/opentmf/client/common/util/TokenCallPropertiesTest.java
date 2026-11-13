package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;

class TokenCallPropertiesTest {

  private static ClientProperties slowBackendClient() {
    var client = new ClientProperties();
    client.setBaseUrl("http://backend");
    client.setRequestTimeout(Duration.ofSeconds(30));
    client.setResponseTimeout(Duration.ofSeconds(120));
    client.setNumRetries(3);
    client.setRetryWaitDuration(Duration.ofSeconds(2));
    client.setFixedHeaders(Map.of("x-api-key", "K"));
    return client;
  }

  @Test
  void bearerAuthDefaults_setNoTokenBoundOfItsOwn() {
    var bearer = new BearerAuthConfig();

    assertThat(bearer.getRequestTimeout()).isNull();
    assertThat(bearer.getResponseTimeout()).isNull();
    assertThat(bearer.getNumRetries()).isNull();
  }

  @Test
  void withoutBearerAuth_theClientsOwnInstance() {
    var client = slowBackendClient();

    assertThat(TokenCallProperties.of(client)).isSameAs(client);
  }

  @Test
  void bearerAuthWithoutTokenBounds_theClientsOwnInstance_soNothingDiffers() {
    var client = slowBackendClient();
    client.setBearerAuth(new BearerAuthConfig());

    assertThat(TokenCallProperties.of(client)).isSameAs(client);
  }

  @Test
  void tokenBoundsSet_aCopyCarriesThem_andTheClientIsUntouched() {
    var client = slowBackendClient();
    var bearer = new BearerAuthConfig();
    bearer.setRequestTimeout(Duration.ofSeconds(2));
    bearer.setResponseTimeout(Duration.ofSeconds(5));
    bearer.setNumRetries(1);
    client.setBearerAuth(bearer);

    var token = TokenCallProperties.of(client);

    assertThat(token).isNotSameAs(client);
    assertThat(token.getRequestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(token.getResponseTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(token.getNumRetries()).isEqualTo(1);
    // everything else is the client's
    assertThat(token.getBaseUrl()).isEqualTo("http://backend");
    assertThat(token.getRetryWaitDuration()).isEqualTo(Duration.ofSeconds(2));
    assertThat(token.getBearerAuth()).isSameAs(bearer);
    assertThat(token.getFixedHeaders()).isSameAs(client.getFixedHeaders());
    assertThat(token.getResilience()).isSameAs(client.getResilience());
    // the client keeps its own bounds for its business calls
    assertThat(client.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(client.getResponseTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(client.getNumRetries()).isEqualTo(3);
  }

  @Test
  void eachTokenBoundAlone_overridesOnlyItself() {
    var client = slowBackendClient();
    var bearer = new BearerAuthConfig();
    bearer.setNumRetries(0);
    client.setBearerAuth(bearer);

    var token = TokenCallProperties.of(client);

    assertThat(token.getNumRetries()).isZero();
    assertThat(token.getRequestTimeout()).isEqualTo(client.getRequestTimeout());
    assertThat(token.getResponseTimeout()).isEqualTo(client.getResponseTimeout());
  }

  @Test
  void aNegativeTokenNumRetries_isRejected() {
    var bearer = new BearerAuthConfig();
    bearer.setNumRetries(-1);

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validateProperty(bearer, "numRetries")).hasSize(1);
    }
  }
}
