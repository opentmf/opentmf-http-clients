package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.test.util.MtlsCertificateUtil;
import org.opentmf.client.test.util.MtlsCertificateUtil.CertificateBundle;
import org.opentmf.client.test.util.TcpTunnelProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
  private ApplicationContext context;

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

  /** Both shapes of both sync backends: the RestTemplate and the RestClient bean. */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"Jdk, RestTemplate", "Jdk, RestClient", "Apache, RestTemplate",
      "Apache, RestClient"})
  void testRestClient_withMtlsAndProxy_httpsGetSucceeds(String backend, String shape) {
    var path = "/proxy-" + backend + "-" + shape + "-get";
    mockGet(path, backend + " proxy+mTLS OK");

    assertThat(SyncShapes.get(context, "proxyRest" + backend + shape, targetBaseUrl + path))
        .isEqualTo(backend + " proxy+mTLS OK");
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"Jdk, RestTemplate", "Jdk, RestClient", "Apache, RestTemplate",
      "Apache, RestClient"})
  void testRestClient_withMtlsAndProxy_httpsPostSucceeds(String backend, String shape) {
    var path = "/proxy-" + backend + "-" + shape + "-post";
    mockPost(path, backend + " proxy post OK");

    assertThat(SyncShapes.post(context, "proxyRest" + backend + shape, targetBaseUrl + path,
        "{\"key\":\"value\"}"))
        .isEqualTo(backend + " proxy post OK");
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
