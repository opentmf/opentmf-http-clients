package org.opentmf.client.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientPropertiesNestedModelsTest {

  @Test
  void proxyConfig_gettersAndSetters() {
    var proxy = new ClientProperties.ProxyConfig();
    proxy.setProxyHost("proxy.example.com");
    proxy.setProxyPort(8080);
    proxy.setNonProxyHosts(List.of("localhost", "127.0.0.1"));

    assertThat(proxy.getProxyHost()).isEqualTo("proxy.example.com");
    assertThat(proxy.getProxyPort()).isEqualTo(8080);
    assertThat(proxy.getNonProxyHosts()).containsExactly("localhost", "127.0.0.1");
  }

  @Test
  void certificates_gettersAndSetters() {
    var certs = new ClientProperties.Certificates();
    assertThat(certs.getKeyStore()).isNull();
    assertThat(certs.getTrustStore()).isNull();

    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks("a2V5");
    ks.setPassword("ksPass");
    ks.setPkPassword("pkPass");
    certs.setKeyStore(ks);

    var ts = new ClientProperties.Certificates.TrustStore();
    ts.setBase64Jks("dHJ1c3Q=");
    ts.setPassword("tsPass");
    certs.setTrustStore(ts);

    assertThat(certs.getKeyStore().getBase64Jks()).isEqualTo("a2V5");
    assertThat(certs.getKeyStore().getPassword()).isEqualTo("ksPass");
    assertThat(certs.getKeyStore().getPkPassword()).isEqualTo("pkPass");
    assertThat(certs.getTrustStore().getBase64Jks()).isEqualTo("dHJ1c3Q=");
    assertThat(certs.getTrustStore().getPassword()).isEqualTo("tsPass");
  }

  @Test
  void pathScope_gettersAndSetters() {
    var pathScope = new ClientProperties.PathScope();
    pathScope.setPath("/api/v1");
    pathScope.setScope("read write");

    assertThat(pathScope.getPath()).isEqualTo("/api/v1");
    assertThat(pathScope.getScope()).isEqualTo("read write");
  }
}
