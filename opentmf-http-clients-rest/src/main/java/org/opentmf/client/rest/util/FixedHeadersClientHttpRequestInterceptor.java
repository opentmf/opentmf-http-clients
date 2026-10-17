package org.opentmf.client.rest.util;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A {@link ClientHttpRequestInterceptor} that applies a client's {@code fixed-headers} as
 * defaults: a header is set only when the request has not already set one with that name, so
 * per-request values always win.
 *
 * <p>The fixed headers belong to the client's API calls. The library removes this interceptor
 * from the {@code RestClient} its token calls run through, so they never reach the token
 * endpoint; {@code bearer-auth.headers} is the opt-in for a header the token endpoint needs.</p>
 */
@NullMarked
public final class FixedHeadersClientHttpRequestInterceptor
    implements ClientHttpRequestInterceptor {

  private final Map<String, String> fixedHeaders;

  public FixedHeadersClientHttpRequestInterceptor(Map<String, String> fixedHeaders) {
    this.fixedHeaders = Objects.requireNonNull(fixedHeaders, "fixedHeaders");
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    fixedHeaders.forEach((name, value) -> {
      if (!request.getHeaders().containsHeader(name)) {
        request.getHeaders().set(name, value);
      }
    });
    return execution.execute(request, body);
  }
}
