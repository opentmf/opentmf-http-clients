package org.opentmf.client.rest.service.api;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * An application-supplied interceptor that the library puts at the <b>head</b> of the chain of
 * every synchronous client it builds. Declare one as a bean, and every library-built client
 * carries it: the {@code <id>RestTemplate} and {@code <id>RestClient} beans, the token calls of
 * {@code <id>TokenService}, and the clients built at runtime through {@code HttpClientRegistry}.
 *
 * <p>Contributions run <b>before</b> every interceptor of the library's own (resilience, gzip,
 * fixed headers, Logbook), in ascending {@link #order()}. Contributions with the same order keep
 * their bean registration order. As everywhere in Spring, an {@code @Order} on the {@code @Bean}
 * method takes precedence over {@link #order()}. Because a contribution runs ahead of the
 * resilience interceptor, a request it rejects by throwing neither counts against the circuit
 * breaker nor takes a bulkhead permit.
 *
 * <p>The contributions are applied to every synchronous client. An interceptor that is meant for
 * some clients only decides that itself, for example from the request URI. The reactive
 * ({@code WebClient}) clients do not take contributions.
 *
 * <p>The library looks up the contribution beans each time it builds a client: at startup for
 * the {@code opentmf.http-clients.*} clients, at registration for the dynamic ones. Declare a
 * contribution in a configuration class that does not itself inject the library's clients, or
 * the two depend on each other.
 *
 * @param interceptor the interceptor to apply
 * @param order the position among the contributions, lowest first
 */
@NullMarked
public record OutboundInterceptorContribution(ClientHttpRequestInterceptor interceptor, int order)
    implements Ordered {

  public OutboundInterceptorContribution {
    Objects.requireNonNull(interceptor, "interceptor");
  }

  /**
   * A contribution with {@link Ordered#LOWEST_PRECEDENCE}, the order Spring gives a bean that
   * declares none.
   */
  public OutboundInterceptorContribution(ClientHttpRequestInterceptor interceptor) {
    this(interceptor, Ordered.LOWEST_PRECEDENCE);
  }

  @Override
  public int getOrder() {
    return order;
  }
}
