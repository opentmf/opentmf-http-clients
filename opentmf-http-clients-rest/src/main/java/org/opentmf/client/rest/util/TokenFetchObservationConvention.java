package org.opentmf.client.rest.util;

import io.micrometer.common.KeyValue;
import org.opentmf.client.common.util.TokenRoute;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * The client observation convention of a {@code RestClient} that fetches bearer tokens: Spring's
 * default, with the {@code uri} label set to the fixed route name {@link TokenRoute#NAME}. A
 * token call goes to a configured address, not a URI template, so the default would show no
 * route ({@code none}); every other label and the observation's name stay Spring's.
 *
 * @since 3.0.0
 */
public class TokenFetchObservationConvention extends DefaultClientRequestObservationConvention {

  private static final KeyValue URI = KeyValue.of(LowCardinalityKeyNames.URI, TokenRoute.NAME);

  @Override
  protected KeyValue uri(ClientRequestObservationContext context) {
    return URI;
  }
}
