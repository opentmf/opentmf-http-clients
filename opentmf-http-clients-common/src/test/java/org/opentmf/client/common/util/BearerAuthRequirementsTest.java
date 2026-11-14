package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @ValueSource(strings = {
      "htps://canaryuser:canarypass7f3a@idp/token", "http://local%20host/token", "http:///token"})
  void aTokenUrlThatCannotBeCalled_isRefused_withoutQuotingIt(String url) {
    var bearer = new BearerAuthConfig();
    bearer.setTokenUrl(URI.create(url));
    var client = bearerClient(bearer);

    assertThatThrownBy(() -> BearerAuthRequirements.requireTokenUrl("store", client))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Client 'store' has a bearer-auth.token-url that is not an absolute http or "
            + "https URL with a host.");
  }

  @Test
  void aTokenUrl_aMockToken_orNoBearerAuth_areAccepted() {
    var withUrl = new BearerAuthConfig();
    withUrl.setTokenUrl(URI.create("http://idp/token"));
    var withUserPart = new BearerAuthConfig();
    withUserPart.setTokenUrl(URI.create("http://canaryuser:canarypass7f3a@idp/token"));
    var mock = new BearerAuthConfig();
    mock.setUseMock(true);
    var basic = new ClientProperties();
    basic.setBasicAuth(new BasicAuthConfig());

    assertThatCode(() -> {
      BearerAuthRequirements.requireTokenUrl("a", bearerClient(withUrl));
      BearerAuthRequirements.requireTokenUrl("e", bearerClient(withUserPart));
      BearerAuthRequirements.requireTokenUrl("b", bearerClient(mock));
      BearerAuthRequirements.requireTokenUrl("c", new ClientProperties());
      BearerAuthRequirements.requireTokenUrl("d", basic);
    }).doesNotThrowAnyException();
  }
}
