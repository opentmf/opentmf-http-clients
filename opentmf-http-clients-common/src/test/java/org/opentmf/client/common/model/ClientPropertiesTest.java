package org.opentmf.client.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClientPropertiesTest {

  @Test
  void defaults() {
    var props = new ClientProperties();

    assertThat(props.getMaxConnections()).isEqualTo(200);
    assertThat(props.getMaxConnectionsPerRoute()).isNull();
    assertThat(props.getRequestTimeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(props.getResponseTimeout()).isEqualTo(Duration.ofSeconds(45));
    assertThat(props.getConnectionIdleTimeout()).isEqualTo(Duration.ofMinutes(4));
    assertThat(props.getNumRetries()).isEqualTo(3);
    assertThat(props.getRetryWaitDuration()).isEqualTo(Duration.ofSeconds(5));
    assertThat(props.isFollowRedirects()).isTrue();
    assertThat(props.getSslProtocol()).isEqualTo("TLS");
    assertThat(props.isLoggingEnabled()).isTrue();
    assertThat(props.isCompressionEnabled()).isTrue();
    assertThat(props.getClientType()).isNull();
    assertThat(props.getBaseUrl()).isNull();
  }

  @Test
  void getAuthType_none_whenNoAuthConfigured() {
    var props = new ClientProperties();
    assertThat(props.getAuthType()).isEqualTo(AuthType.NONE);
  }

  @Test
  void getAuthType_basic_whenBasicAuthSet() {
    var props = new ClientProperties();
    props.setBasicAuth(new BasicAuthConfig("user", "pass", "US-ASCII"));
    assertThat(props.getAuthType()).isEqualTo(AuthType.BASIC);
  }

  @Test
  void getAuthType_bearer_whenBearerAuthSet() {
    var props = new ClientProperties();
    props.setBearerAuth(new BearerAuthConfig());
    assertThat(props.getAuthType()).isEqualTo(AuthType.BEARER);
  }

  @Test
  void getEffectiveMaxConnectionsPerRoute_returnsExplicitValue_whenSet() {
    var props = new ClientProperties();
    props.setMaxConnectionsPerRoute(50);
    assertThat(props.getEffectiveMaxConnectionsPerRoute()).isEqualTo(50);
  }

  @Test
  void getEffectiveMaxConnectionsPerRoute_fallsBackToMaxConnections_whenNull() {
    var props = new ClientProperties();
    props.setMaxConnections(200);
    assertThat(props.getEffectiveMaxConnectionsPerRoute()).isEqualTo(200);
  }
}
