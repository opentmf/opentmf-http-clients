package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.test.util.MtlsCertificateUtil;
import org.opentmf.client.test.util.MtlsCertificateUtil.CertificateBundle;
import org.opentmf.client.test.util.TcpTunnelProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * Integration tests verifying that clients configured with both mTLS certificates and a forward
 * proxy can successfully communicate over HTTPS through a true TCP tunnel.
 *
 * <p>The proxy is a minimal {@link TcpTunnelProxy} that implements HTTP CONNECT and blindly
 * relays bytes without terminating or inspecting TLS. This means the TLS handshake (including
 * mutual certificate exchange) happens end-to-end between the client and the target MockServer,
 * faithfully simulating a production forward proxy.</p>
 *
 * <p>Three client types are tested: reactive (WebClient), REST with JDK HttpClient,
 * and REST with Apache HttpClient 5.</p>
 */
@SpringBootTest
class MtlsProxyIT {

  private static final CertificateBundle CERTS = MtlsCertificateUtil.generate();

  private static final TcpTunnelProxy tunnelProxy = new TcpTunnelProxy();
  private static ClientAndServer targetServer;
  private static String targetBaseUrl;

  @DynamicPropertySource
  static void configureClients(DynamicPropertyRegistry registry) {
    configureClientWithProxy(registry, "proxyReactive", "netty");
    configureClientWithProxy(registry, "proxyRestJdk", "jdk");
    configureClientWithProxy(registry, "proxyRestApache", "apache");
  }

  private static void configureClientWithProxy(
      DynamicPropertyRegistry registry, String clientId, String clientType) {
    String prefix = "opentmf.http-clients." + clientId + ".";
    registry.add(prefix + "client-type", () -> clientType);
    registry.add(prefix + "certificates.key-store.base64-jks", CERTS::clientKeyStoreBase64);
    registry.add(prefix + "certificates.key-store.password", CERTS::password);
    registry.add(prefix + "certificates.key-store.pk-password", CERTS::password);
    registry.add(prefix + "certificates.trust-store.base64-jks", CERTS::clientTrustStoreBase64);
    registry.add(prefix + "certificates.trust-store.password", CERTS::password);
    registry.add(prefix + "proxy-config.proxy-host", () -> "localhost");
    registry.add(prefix + "proxy-config.proxy-port", tunnelProxy::getPort);
  }

  @BeforeAll
  static void startServers() {
    tunnelProxy.start();
    targetServer = ClientAndServer.startClientAndServer();
    targetBaseUrl = "https://localhost:" + targetServer.getLocalPort();
  }

  @AfterAll
  static void stopServers() {
    tunnelProxy.stop();
    if (targetServer != null && targetServer.isRunning()) {
      targetServer.stop();
    }
  }

  @BeforeEach
  void resetTarget() {
    targetServer.reset();
  }

  @Autowired
  @Qualifier("proxyReactiveWebClient")
  private WebClient proxyReactiveWebClient;

  @Autowired
  @Qualifier("proxyRestJdkRestTemplate")
  private RestTemplate proxyRestJdkRestTemplate;

  @Autowired
  @Qualifier("proxyRestApacheRestTemplate")
  private RestTemplate proxyRestApacheRestTemplate;

  // --- Positive tests: mTLS + proxy through TCP tunnel ---

  @Test
  void testReactiveClient_withMtlsAndProxy_httpsGetSucceeds() {
    mockGet("/proxy-reactive-get", "reactive proxy+mTLS OK");

    StepVerifier.create(
            proxyReactiveWebClient.get()
                .uri(targetBaseUrl + "/proxy-reactive-get")
                .retrieve()
                .bodyToMono(String.class))
        .expectNext("reactive proxy+mTLS OK")
        .verifyComplete();
  }

  @Test
  void testReactiveClient_withMtlsAndProxy_httpsPostSucceeds() {
    mockPost("/proxy-reactive-post", "reactive proxy post OK");

    StepVerifier.create(
            proxyReactiveWebClient.post()
                .uri(targetBaseUrl + "/proxy-reactive-post")
                .bodyValue("{\"key\":\"value\"}")
                .retrieve()
                .bodyToMono(String.class))
        .expectNext("reactive proxy post OK")
        .verifyComplete();
  }

  @Test
  void testRestJdkClient_withMtlsAndProxy_httpsGetSucceeds() {
    mockGet("/proxy-jdk-get", "jdk proxy+mTLS OK");

    String result = proxyRestJdkRestTemplate.getForObject(
        targetBaseUrl + "/proxy-jdk-get", String.class);
    assertThat(result).isEqualTo("jdk proxy+mTLS OK");
  }

  @Test
  void testRestJdkClient_withMtlsAndProxy_httpsPostSucceeds() {
    mockPost("/proxy-jdk-post", "jdk proxy post OK");

    String result = proxyRestJdkRestTemplate.postForObject(
        targetBaseUrl + "/proxy-jdk-post", "{\"key\":\"value\"}", String.class);
    assertThat(result).isEqualTo("jdk proxy post OK");
  }

  @Test
  void testRestApacheClient_withMtlsAndProxy_httpsGetSucceeds() {
    mockGet("/proxy-apache-get", "apache proxy+mTLS OK");

    String result = proxyRestApacheRestTemplate.getForObject(
        targetBaseUrl + "/proxy-apache-get", String.class);
    assertThat(result).isEqualTo("apache proxy+mTLS OK");
  }

  @Test
  void testRestApacheClient_withMtlsAndProxy_httpsPostSucceeds() {
    mockPost("/proxy-apache-post", "apache proxy post OK");

    String result = proxyRestApacheRestTemplate.postForObject(
        targetBaseUrl + "/proxy-apache-post", "{\"key\":\"value\"}", String.class);
    assertThat(result).isEqualTo("apache proxy post OK");
  }

  // --- Helpers ---

  private void mockGet(String path, String responseBody) {
    targetServer
        .when(request().withMethod("GET").withPath(path))
        .respond(response().withBody(responseBody).withStatusCode(200));
  }

  private void mockPost(String path, String responseBody) {
    targetServer
        .when(request().withMethod("POST").withPath(path))
        .respond(response().withBody(responseBody).withStatusCode(200));
  }
}
