package org.opentmf.client.reactive.util;

import java.util.function.Consumer;
import org.opentmf.client.common.util.InvalidTokenRefusals;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reports an API's refusal of the call's bearer token as invalid (see
 * {@link InvalidTokenRefusals}) to the client's token service, which then drops that token from
 * its cache. The answer passes through untouched: the refused call fails exactly as it would
 * without this filter, and nothing is retried. Until a token service is bound, a refusal is
 * ignored.
 *
 * @since 2.5.2
 */
public final class RefusedTokenFilter implements ExchangeFilterFunction {

  private volatile Consumer<String> onRefused = token -> { };

  @Override
  public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    return next.exchange(request).doOnNext(response -> InvalidTokenRefusals
        .refusedToken(response.statusCode().value(),
            response.headers().header(HttpHeaders.WWW_AUTHENTICATE),
            request.headers().getFirst(HttpHeaders.AUTHORIZATION))
        .ifPresent(onRefused));
  }

  /** Sends each refused token to {@code onRefused} from now on. */
  public void bind(Consumer<String> onRefused) {
    this.onRefused = onRefused;
  }

  /**
   * Binds every {@code RefusedTokenFilter} of {@code webClient} to {@code onRefused}.
   *
   * @return whether the client has one
   */
  public static boolean bind(WebClient webClient, Consumer<String> onRefused) {
    var found = new boolean[1];
    webClient.mutate().filters(filters -> filters.stream()
        .filter(RefusedTokenFilter.class::isInstance)
        .forEach(filter -> {
          ((RefusedTokenFilter) filter).bind(onRefused);
          found[0] = true;
        }));
    return found[0];
  }
}
