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

@Validated
@Getter
@Setter
public class ClientProperties {

  private String baseUrl;

  @Positive
  private int maxConnections = 200;

  /**
   * Maximum number of connections per route (host). Currently only honoured by
   * Apache HttpClient 5 ({@code client-type: apache}); ignored by JDK HttpClient
   * and Reactor Netty. When {@code null}, defaults to {@link #maxConnections}.
   */
  private Integer maxConnectionsPerRoute;

  private Duration requestTimeout = Duration.ofSeconds(30);

  private Duration responseTimeout = Duration.ofSeconds(45);

  private Duration connectionIdleTimeout = Duration.ofMinutes(4);

  @PositiveOrZero
  private int numRetries = 3;

  private Duration retryWaitDuration = Duration.ofSeconds(5);

  private boolean followRedirects = true;

  private String sslProtocol = "TLS";

  private boolean loggingEnabled = true;

  private boolean compressionEnabled = true;

  private Map<String, String> fixedHeaders;

  private ProxyConfig proxyConfig;

  private Certificates certificates;

  @Valid
  private BasicAuthConfig basicAuth;

  @Valid
  private BearerAuthConfig bearerAuth;

  private Map<@NotEmpty String, @Valid PathScope> paths;

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


  @Validated
  @Getter
  @Setter
  public static class ProxyConfig {

    @NotBlank
    private String proxyHost;

    @Positive
    private int proxyPort;

    private List<String> nonProxyHosts;
  }

  @Validated
  @Getter
  @Setter
  public static class Certificates {

    @Valid
    private KeyStore keyStore;

    private TrustStore trustStore;

    @Validated
    @Getter
    @Setter
    public static class KeyStore {
      String password;

      @NotBlank
      String pkPassword;

      @NotBlank
      String base64Jks;
    }

    @Validated
    @Getter
    @Setter
    public static class TrustStore {
      String password;

      @NotBlank
      String base64Jks;
    }
  }

  @Validated
  @Getter
  @Setter
  public static class PathScope {

    @NotBlank
    private String path;

    private String scope;
  }
}
