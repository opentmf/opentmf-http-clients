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

@ConfigurationProperties(prefix = "opentmf")
@Getter
@Setter
@Validated
public class OpentmfHttpClientsConfig {

  private ClientType clientType = ClientType.JDK;

  @NotEmpty
  private Map<@NotEmpty String, @Valid ClientProperties> httpClients;

  public ClientType resolveClientType(ClientProperties props) {
    return props.getClientType() != null ? props.getClientType() : clientType;
  }
}
