package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.util.TokenRoute;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

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
    var request = mock(ClientHttpRequest.class);
    when(request.getURI()).thenReturn(URI.create(address));
    when(request.getMethod()).thenReturn(HttpMethod.POST);
    return new ClientRequestObservationContext(request);
  }
}
