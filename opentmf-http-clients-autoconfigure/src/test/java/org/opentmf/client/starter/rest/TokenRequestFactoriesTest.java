package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

class TokenRequestFactoriesTest {

  private static ClientProperties client() {
    var client = new ClientProperties();
    client.setRequestTimeout(Duration.ofSeconds(30));
    client.setResponseTimeout(Duration.ofSeconds(120));
    return client;
  }

  private static ClientProperties token(Duration requestTimeout, Duration responseTimeout) {
    var token = client();
    token.setRequestTimeout(requestTimeout);
    token.setResponseTimeout(responseTimeout);
    return token;
  }

  @Test
  void theClientsOwnTimeouts_noFactoryOfItsOwn() {
    var jdk = new CloseableJdkClientHttpRequestFactory(HttpClient.newHttpClient());

    assertThat(TokenRequestFactories.forTokenCalls(jdk, client(), client())).isNull();
  }

  @Test
  void apache_theTokenFactorySendsOverTheClientsOwnHttpClient() throws Exception {
    try (var httpClient = HttpClients.createDefault()) {
      var apache = new HttpComponentsClientHttpRequestFactory(httpClient);

      var tokenFactory = TokenRequestFactories.forTokenCalls(apache, client(),
          token(Duration.ofSeconds(2), Duration.ofSeconds(5)));

      assertThat(tokenFactory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class)
          .isNotSameAs(apache);
      assertThat(((HttpComponentsClientHttpRequestFactory) tokenFactory).getHttpClient())
          .isSameAs(httpClient);
    }
  }

  @Test
  void jdk_responseTimeoutOnly_noCompanionClient() {
    var jdk = new CloseableJdkClientHttpRequestFactory(HttpClient.newHttpClient());

    var tokenFactory = TokenRequestFactories.forTokenCalls(jdk, client(),
        token(Duration.ofSeconds(30), Duration.ofSeconds(5)));

    assertThat(tokenFactory).isInstanceOf(JdkClientHttpRequestFactory.class).isNotSameAs(jdk);
  }

  @Test
  void jdk_requestTimeout_aCompanionClient_closedWithTheClientsOwn() throws Exception {
    var jdk = new CloseableJdkClientHttpRequestFactory(HttpClient.newHttpClient());

    var tokenFactory = TokenRequestFactories.forTokenCalls(jdk, client(),
        token(Duration.ofSeconds(2), Duration.ofSeconds(120)));

    assertThat(tokenFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
    jdk.destroy();
  }

  @Test
  void jdk_theCompanionClient_keepsTheClientsSettings_withItsOwnConnectTimeout() {
    var proxy = ProxySelector.of(new InetSocketAddress("proxy.example", 3128));
    var original = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(proxy)
        .build();

    var companion = TokenRequestFactories.withConnectTimeout(original, Duration.ofSeconds(2));

    assertThat(companion).isNotSameAs(original);
    assertThat(companion.connectTimeout()).contains(Duration.ofSeconds(2));
    assertThat(companion.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    assertThat(companion.proxy()).contains(proxy);
    assertThat(companion.sslContext()).isSameAs(original.sslContext());
    assertThat(companion.version()).isEqualTo(original.version());
  }

  @Test
  void aRequestFactoryThisLibraryDidNotBuild_failsByName() {
    var other = new SimpleClientHttpRequestFactory();
    var client = client();
    var token = token(Duration.ofSeconds(2), Duration.ofSeconds(5));

    assertThatThrownBy(() -> TokenRequestFactories.forTokenCalls(other, client, token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bearer-auth.response-timeout")
        .hasMessageContaining(SimpleClientHttpRequestFactory.class.getName());
  }
}
