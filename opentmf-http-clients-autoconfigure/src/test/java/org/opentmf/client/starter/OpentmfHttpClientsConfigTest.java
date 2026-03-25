package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;

class OpentmfHttpClientsConfigTest {

  @Test
  void resolveClientType_usesPerClientOverride() {
    var config = new OpentmfHttpClientsConfig();
    config.setClientType(ClientType.JDK);

    var props = new ClientProperties();
    props.setClientType(ClientType.APACHE);

    assertThat(config.resolveClientType(props)).isEqualTo(ClientType.APACHE);
  }

  @Test
  void resolveClientType_fallsBackToGlobal() {
    var config = new OpentmfHttpClientsConfig();
    config.setClientType(ClientType.APACHE);

    var props = new ClientProperties();

    assertThat(config.resolveClientType(props)).isEqualTo(ClientType.APACHE);
  }

  @Test
  void defaultClientType_isJdk() {
    var config = new OpentmfHttpClientsConfig();
    assertThat(config.getClientType()).isEqualTo(ClientType.JDK);
  }
}
