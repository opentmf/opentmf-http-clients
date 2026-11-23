package org.opentmf.client.rest.util;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.opentmf.client.common.util.InvalidTokenRefusals;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Reports an API's refusal of the call's bearer token as invalid (see
 * {@link InvalidTokenRefusals}) to the client's token service, which then drops that token from
 * its cache. The answer is returned untouched: the refused call fails exactly as it would without
 * this interceptor, and nothing is retried. Until a token service is bound, a refusal is ignored.
 *
 * @since 2.5.2
 */
public final class RefusedTokenInterceptor implements ClientHttpRequestInterceptor {

  private final AtomicReference<Consumer<String>> onRefused =
      new AtomicReference<>(token -> { });

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    var response = execution.execute(request, body);
    InvalidTokenRefusals.refusedToken(response.getStatusCode().value(),
            response.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE),
            request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
        .ifPresent(onRefused.get());
    return response;
  }

  /** Sends each refused token to {@code onRefused} from now on. */
  public void bind(Consumer<String> onRefused) {
    this.onRefused.set(onRefused);
  }

  /**
   * Binds every {@code RefusedTokenInterceptor} of {@code restClient} to {@code onRefused}.
   *
   * @return whether the client has one
   */
  public static boolean bind(RestClient restClient, Consumer<String> onRefused) {
    var found = new boolean[1];
    restClient.mutate().requestInterceptors(interceptors -> interceptors.stream()
        .filter(RefusedTokenInterceptor.class::isInstance)
        .forEach(interceptor -> {
          ((RefusedTokenInterceptor) interceptor).bind(onRefused);
          found[0] = true;
        }));
    return found[0];
  }
}
