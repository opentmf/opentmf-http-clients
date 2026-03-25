package org.opentmf.client.common.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

@Validated
@Getter
@Setter
public class ClientProperties {

  @Positive
  private int maxConnections = 500;

  @Positive
  private int requestTimeoutMillis = 30000;

  @Positive
  private long responseTimeoutMillis = 45000;

  @PositiveOrZero
  private int numRetries = 3;

  @Positive
  private long retryWaitMillis = 5000;

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
  private boolean isAuthMutuallyExclusive() {
    return basicAuth == null || bearerAuth == null;
  }

  public AuthType getAuthType() {
    if (basicAuth != null) return AuthType.BASIC;
    if (bearerAuth != null) return AuthType.BEARER;
    return AuthType.NONE;
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
