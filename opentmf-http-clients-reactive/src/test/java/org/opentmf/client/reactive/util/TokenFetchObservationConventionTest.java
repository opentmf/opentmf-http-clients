package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.util.TokenRoute;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

class TokenFetchObservationConventionTest {

  private final TokenFetchObservationConvention convention = new TokenFetchObservationConvention();

  @Test
  void theUriLabel_isTheRouteName_andNotTheTokenAddress() {
    var context = tokenCall("https://idp.example/realms/test/protocol/openid-connect/token");

    assertThat(convention.getLowCardinalityKeyValues(context).stream()
        .filter(keyValue -> keyValue.getKey().equals("uri")))
        .singleElement()
        .satisfies(keyValue -> assertThat(keyValue.getValue()).isEqualTo(TokenRoute.NAME));
  }

  @Test
  void everythingElse_isSpringsDefault() {
    var context = tokenCall("https://idp.example/token");
    var spring = new DefaultClientRequestObservationConvention();

    assertThat(convention.getName()).isEqualTo(spring.getName());
    assertThat(convention.getLowCardinalityKeyValues(context).stream()
        .filter(keyValue -> !keyValue.getKey().equals("uri")))
        .containsExactlyInAnyOrderElementsOf(spring.getLowCardinalityKeyValues(context).stream()
            .filter(keyValue -> !keyValue.getKey().equals("uri")).toList());
  }

  private static ClientRequestObservationContext tokenCall(String address) {
    return new ClientRequestObservationContext(
        ClientRequest.create(HttpMethod.POST, URI.create(address)));
  }
}
