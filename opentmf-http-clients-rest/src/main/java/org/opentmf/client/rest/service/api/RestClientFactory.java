package org.opentmf.client.rest.service.api;

import org.opentmf.client.common.model.ClientProperties;
import org.springframework.web.client.RestClient;

/**
 * Builds a fully configured {@link RestClient} for a client: the backend's HTTP client with its
 * mTLS, pool and time limits, the application's {@link OutboundInterceptorContribution}s, the
 * library's resilience, fixed-headers and Logbook interceptors in their order, the
 * application's {@code ObservationRegistry}, the base URL, and the library's error handling
 * ({@code OpenTmfClientResponseException}, {@code OpenTmfClientNotFoundException} for 404).
 *
 * <p>The library's JDK and Apache backends implement this interface, so an application can use
 * it as the marker that the library's synchronous clients are present (for example
 * {@code @ConditionalOnBean(RestClientFactory.class)}). Each call builds a new client with its own
 * connection pool.</p>
 *
 * @since 3.0.0
 */
public interface RestClientFactory {

  /**
   * Builds the {@code RestClient} of the client named {@code clientId}.
   *
   * @throws IllegalArgumentException when the client cannot be built from the properties
   */
  RestClient createRestClient(String clientId, ClientProperties properties);
}
