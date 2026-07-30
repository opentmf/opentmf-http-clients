package org.opentmf.client.starter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Root configuration of the opentmf-http-clients starter, bound to the {@code opentmf} prefix.
 */
@ConfigurationProperties(prefix = "opentmf")
@Getter
@Setter
@Validated
public class OpentmfHttpClientsConfig {

  /**
   * Default HTTP client implementation used by every configured client that does not set its own
   * client-type. Accepted values: jdk (synchronous, JDK HttpClient — the default), apache
   * (synchronous, Apache HttpClient 5), netty (reactive, Reactor Netty), plus the aliases rest and
   * servlet (both jdk) and reactive (netty).
   */
  private ClientType clientType = ClientType.JDK;

  /**
   * The HTTP clients to create, keyed by client id. For each entry the starter registers the beans
   * "[id]RestTemplate" and "[id]RestClient" (synchronous client types) or "[id]WebClient" (netty),
   * plus an "[id]TokenService" when bearer-auth is configured. See ClientProperties for the
   * per-client settings.
   */
  @NotEmpty
  private Map<@NotEmpty String, @Valid ClientProperties> httpClients;

  public ClientType resolveClientType(ClientProperties props) {
    return props.getClientType() != null ? props.getClientType() : clientType;
  }
}
