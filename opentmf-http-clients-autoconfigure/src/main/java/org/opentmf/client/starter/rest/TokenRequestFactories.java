package org.opentmf.client.starter.rest;

import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * The request factory a client's bearer token calls run on when {@code bearer-auth} sets the
 * token request's own {@code request-timeout} or {@code response-timeout}. It sends over the
 * client's own HTTP client, so the token calls keep sharing its connection pool, TLS and proxy
 * settings; only the token request's timeouts differ.
 *
 * <ul>
 *   <li>Apache: the same {@code HttpClient}, with the token's connection-request and response
 *   timeouts applied per request.</li>
 *   <li>JDK: the same {@code HttpClient} with the token's response timeout applied per request.
 *   A token {@code request-timeout} is the JDK client's connect timeout, which only a client of
 *   its own can carry: a companion {@code HttpClient} with the same settings is built for the
 *   token calls and closed together with the client's own.</li>
 * </ul>
 */
final class TokenRequestFactories {

  private TokenRequestFactories() {
  }

  /**
   * The token calls' own request factory, or {@code null} when the token settings carry the
   * client's own timeouts, in which case the token calls run on the client's request factory,
   * exactly as before.
   *
   * @param clientFactory the client's own (raw, non-intercepting) request factory
   * @throws IllegalStateException for a request factory this library did not build
   */
  static @Nullable ClientHttpRequestFactory forTokenCalls(ClientHttpRequestFactory clientFactory,
      ClientProperties client, ClientProperties token) {
    boolean ownRequestTimeout = !client.getRequestTimeout().equals(token.getRequestTimeout());
    boolean ownResponseTimeout = !client.getResponseTimeout().equals(token.getResponseTimeout());
    if (!ownRequestTimeout && !ownResponseTimeout) {
      return null;
    }
    if (clientFactory instanceof HttpComponentsClientHttpRequestFactory apache) {
      var factory = new HttpComponentsClientHttpRequestFactory(apache.getHttpClient());
      factory.setConnectionRequestTimeout(token.getRequestTimeout());
      factory.setReadTimeout(token.getResponseTimeout());
      return factory;
    }
    if (clientFactory instanceof CloseableJdkClientHttpRequestFactory jdk) {
      var httpClient = jdk.httpClient();
      if (ownRequestTimeout) {
        httpClient = withConnectTimeout(httpClient, token.getRequestTimeout());
        jdk.closeAlso(httpClient);
      }
      var factory = new JdkClientHttpRequestFactory(httpClient);
      factory.setReadTimeout(token.getResponseTimeout());
      return factory;
    }
    throw new IllegalStateException("bearer-auth.request-timeout / bearer-auth.response-timeout "
        + "are supported on the request factories this library builds (Apache, JDK), not on "
        + clientFactory.getClass().getName());
  }

  /** A client with the given one's settings and its own connect timeout. */
  static HttpClient withConnectTimeout(HttpClient client, Duration connectTimeout) {
    var builder = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(client.followRedirects())
        .version(client.version())
        .sslContext(client.sslContext())
        .sslParameters(client.sslParameters());
    client.proxy().ifPresent(builder::proxy);
    client.executor().ifPresent(builder::executor);
    client.authenticator().ifPresent(builder::authenticator);
    client.cookieHandler().ifPresent(builder::cookieHandler);
    return builder.build();
  }
}
