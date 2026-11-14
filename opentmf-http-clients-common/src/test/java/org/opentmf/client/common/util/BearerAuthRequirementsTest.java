package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BasicAuthConfig;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;

class BearerAuthRequirementsTest {

  private static ClientProperties bearerClient(BearerAuthConfig bearer) {
    var client = new ClientProperties();
    client.setBearerAuth(bearer);
    return client;
  }

  @Test
  void bearerAuthWithoutTokenUrl_isRefused_namingTheClient() {
    var bearer = new BearerAuthConfig();
    bearer.setNumRetries(0);
    var client = bearerClient(bearer);

    assertThatThrownBy(() -> BearerAuthRequirements.requireTokenUrl("store", client))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Client 'store' has bearer-auth but no bearer-auth.token-url")
        .hasMessageContaining("bearer-auth.use-mock: true");
  }

  @Test
  void withoutAClientId_theRefusalSaysAClient() {
    var client = bearerClient(new BearerAuthConfig());

    assertThatThrownBy(() -> BearerAuthRequirements.requireTokenUrl(null, client))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("A client has bearer-auth but no bearer-auth.token-url");
  }

  @Test
  void aTokenUrl_aMockToken_orNoBearerAuth_areAccepted() {
    var withUrl = new BearerAuthConfig();
    withUrl.setTokenUrl(URI.create("http://idp/token"));
    var mock = new BearerAuthConfig();
    mock.setUseMock(true);
    var basic = new ClientProperties();
    basic.setBasicAuth(new BasicAuthConfig());

    assertThatCode(() -> {
      BearerAuthRequirements.requireTokenUrl("a", bearerClient(withUrl));
      BearerAuthRequirements.requireTokenUrl("b", bearerClient(mock));
      BearerAuthRequirements.requireTokenUrl("c", new ClientProperties());
      BearerAuthRequirements.requireTokenUrl("d", basic);
    }).doesNotThrowAnyException();
  }
}
