package org.opentmf.client.common.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of a single named HTTP client, bound from
 * {@code opentmf.http-clients.<clientId>.*}.
 */
@Validated
@Getter
@Setter
public class ClientProperties {

  /**
   * Base URL prepended to all relative request URIs of this client. When unset, request URIs must
   * be absolute.
   */
  private String baseUrl;

  /**
   * Maximum total number of pooled connections. Honoured by Apache HttpClient 5 and Reactor Netty;
   * ignored by the JDK HttpClient, which manages its connection pool internally. Default 200.
   */
  @Positive
  private int maxConnections = 200;

  /**
   * Maximum number of connections per route (host). Currently only honoured by
   * Apache HttpClient 5 ({@code client-type: apache}); ignored by JDK HttpClient
   * and Reactor Netty. When {@code null}, defaults to {@link #maxConnections}.
   */
  private Integer maxConnectionsPerRoute;

  /**
   * Time allowed for obtaining a usable connection. On JDK and Netty this is the TCP connect
   * timeout. On Apache it is the time to lease a connection from the pool
   * (connection-request timeout); Apache's actual TCP connect timeout stays at the HttpClient 5
   * default. Default 30s.
   */
  private Duration requestTimeout = Duration.ofSeconds(30);

  /**
   * Time to wait for response data (read timeout) on all client types. Netty additionally applies
   * it to the TLS handshake and to the proxy CONNECT exchange. Default 45s.
   */
  private Duration responseTimeout = Duration.ofSeconds(45);

  /**
   * How long pooled connections may stay idle before being evicted (also the connection
   * time-to-live on Apache, and {@code maxIdleTime} on Netty). Ignored by the JDK client.
   * Default 4m.
   */
  private Duration connectionIdleTimeout = Duration.ofMinutes(4);

  /**
   * Maximum number of retry attempts. Used internally only for bearer-token retrieval; regular
   * HTTP calls are never retried automatically — pass this value to
   * {@code SyncClientUtil.executeWithRetry(...)} or {@code WebClientUtil.retry(...)} to opt in at
   * the call site. Default 3.
   */
  @PositiveOrZero
  private int numRetries = 3;

  /**
   * Base wait between retry attempts; subsequent attempts back off exponentially. Default 5s.
   */
  private Duration retryWaitDuration = Duration.ofSeconds(5);

  /**
   * Whether HTTP 3xx redirects are followed automatically. Default true.
   */
  private boolean followRedirects = true;

  /**
   * Protocol passed to {@code SSLContext.getInstance(...)} when {@link #certificates} are
   * configured, e.g. {@code TLS}, {@code TLSv1.2}, {@code TLSv1.3}. Default {@code TLS}.
   */
  private String sslProtocol = "TLS";

  /**
   * Whether Zalando Logbook request/response logging is wired for this client. Requires the
   * matching Logbook artifacts on the classpath — otherwise a warning is logged and requests
   * proceed without logging. Default true.
   */
  private boolean loggingEnabled = true;

  /**
   * Whether HTTP compression is enabled: Reactor Netty uses its built-in {@code compress()},
   * Apache its built-in content compression, and the JDK client a transparent gzip interceptor.
   * Default true.
   */
  private boolean compressionEnabled = true;

  /**
   * Headers added to every request as defaults: a fixed header is applied only when the request
   * has not already set a header with that name, so per-request values always win.
   */
  private Map<String, String> fixedHeaders;

  /**
   * Forward proxy settings. When set, requests are routed through the proxy; TLS traffic is
   * tunneled end-to-end via HTTP CONNECT, so mTLS works through the proxy.
   */
  private ProxyConfig proxyConfig;

  /**
   * Mutual TLS (mTLS) material: a base64-encoded JKS key store with the client identity and an
   * optional trust store.
   */
  private Certificates certificates;

  /**
   * HTTP Basic authentication credentials, attached to every request of this client. Mutually
   * exclusive with {@link #bearerAuth}.
   */
  @Valid
  private BasicAuthConfig basicAuth;

  /**
   * OAuth2 bearer-token configuration. When present, the starter additionally registers an
   * "&lt;id&gt;TokenService" bean that retrieves and caches tokens for this client. Mutually
   * exclusive with {@link #basicAuth}.
   */
  @Valid
  private BearerAuthConfig bearerAuth;

  /**
   * Named path/scope entries for the consuming application to look up (e.g. the request path and
   * the OAuth2 scope to request per operation). The library itself does not interpret these
   * values.
   */
  private Map<@NotEmpty String, @Valid PathScope> paths;

  /**
   * Optional resilience4j decoration (circuit breaker, bulkhead, reactive time limiter) applied
   * to every client shape of this id. Disabled by default; requires the resilience4j jars on the
   * classpath when enabled.
   */
  @Valid
  private ResilienceProperties resilience = new ResilienceProperties();

  /**
   * Overrides the global {@code opentmf.client-type} for this client only.
   */
  private ClientType clientType;

  @AssertTrue(message = "Cannot specify both basic-auth and bearer-auth")
  @SuppressWarnings("unused") // invoked reflectively by Bean Validation
  private boolean isAuthMutuallyExclusive() {
    return basicAuth == null || bearerAuth == null;
  }

  public AuthType getAuthType() {
    if (basicAuth != null) return AuthType.BASIC;
    if (bearerAuth != null) return AuthType.BEARER;
    return AuthType.NONE;
  }

  /**
   * Returns {@link #maxConnectionsPerRoute} if set, otherwise falls back to {@link #maxConnections}.
   */
  public int getEffectiveMaxConnectionsPerRoute() {
    return maxConnectionsPerRoute != null ? maxConnectionsPerRoute : maxConnections;
  }


  /**
   * Forward proxy settings for a client.
   */
  @Validated
  @Getter
  @Setter
  public static class ProxyConfig {

    /**
     * Host name or IP address of the forward proxy.
     */
    @NotBlank
    private String proxyHost;

    /**
     * Port of the forward proxy.
     */
    @Positive
    private int proxyPort;

    /**
     * Hosts to connect to directly, bypassing the proxy. Currently only honoured by Reactor Netty
     * ({@code client-type: netty}); the JDK and Apache clients route all traffic through the
     * proxy.
     */
    private List<String> nonProxyHosts;
  }

  /**
   * Mutual TLS (mTLS) material for a client.
   */
  @Validated
  @Getter
  @Setter
  public static class Certificates {

    /**
     * Key store holding this client's certificate and private key (the mTLS client identity).
     */
    @Valid
    private KeyStore keyStore;

    /**
     * Optional trust store with the CA or server certificates to trust. When unset, the JVM
     * default trust store is used.
     */
    private TrustStore trustStore;

    /**
     * A base64-encoded JKS key store.
     */
    @Validated
    @Getter
    @Setter
    public static class KeyStore {

      /**
       * Password of the key store itself. May be unset for a password-less store.
       */
      String password;

      /**
       * Password of the private key inside the key store.
       */
      @NotBlank
      String pkPassword;

      /**
       * The JKS key store content, encoded as base64.
       */
      @NotBlank
      String base64Jks;
    }

    /**
     * A base64-encoded JKS trust store.
     */
    @Validated
    @Getter
    @Setter
    public static class TrustStore {

      /**
       * Password of the trust store. May be unset for a password-less store.
       */
      String password;

      /**
       * The JKS trust store content, encoded as base64.
       */
      @NotBlank
      String base64Jks;
    }
  }

  /**
   * A named path/scope entry. The library does not interpret these values; they are a lookup
   * table for the consuming application.
   */
  @Validated
  @Getter
  @Setter
  public static class PathScope {

    /**
     * Request path of the operation, typically relative to {@code base-url}.
     */
    @NotBlank
    private String path;

    /**
     * OAuth2 scope associated with the operation, e.g. to pass as additional scope when
     * requesting a token.
     */
    private String scope;
  }
}
