package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.test.util.MtlsCertificateUtil;
import org.opentmf.client.test.util.MtlsCertificateUtil.CertificateBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

/**
 * Integration tests verifying that clients configured with mTLS certificates (keystore + truststore)
 * can communicate over HTTPS. This exercises the SSL configuration in WebClientConfigUtil,
 * JdkRestTemplateFactory, and ApacheRestTemplateFactory.
 *
 * <p>Three client types are tested: reactive (WebClient), REST with JDK HttpClient,
 * and REST with Apache HttpClient 5. Negative tests verify that clients without
 * proper SSL configuration fail against the HTTPS server.</p>
 */
@SpringBootTest
class MtlsIT {

  private static final CertificateBundle CERTS = MtlsCertificateUtil.generate();
  private static ClientAndServer httpsServer;
  private static String httpsBaseUrl;

  @DynamicPropertySource
  static void configureMtlsClients(DynamicPropertyRegistry registry) {
    configureCertificates(registry, "mtlsReactive");
    registry.add("opentmf.http-clients.mtlsReactive.client-type", () -> "netty");

    configureCertificates(registry, "mtlsRestJdk");
    registry.add("opentmf.http-clients.mtlsRestJdk.client-type", () -> "jdk");

    configureCertificates(registry, "mtlsRestApache");
    registry.add("opentmf.http-clients.mtlsRestApache.client-type", () -> "apache");
  }

  private static void configureCertificates(DynamicPropertyRegistry registry, String clientId) {
    String prefix = "opentmf.http-clients." + clientId + ".certificates.";
    registry.add(prefix + "key-store.base64-jks", CERTS::clientKeyStoreBase64);
    registry.add(prefix + "key-store.password", CERTS::password);
    registry.add(prefix + "key-store.pk-password", CERTS::password);
    registry.add(prefix + "trust-store.base64-jks", CERTS::clientTrustStoreBase64);
    registry.add(prefix + "trust-store.password", CERTS::password);
  }

  @BeforeAll
  static void startHttpsServer() {
    httpsServer = ClientAndServer.startClientAndServer();
    httpsBaseUrl = "https://localhost:" + httpsServer.getLocalPort();
  }

  @AfterAll
  static void stopHttpsServer() {
    if (httpsServer != null && httpsServer.isRunning()) {
      httpsServer.stop();
    }
  }

  @BeforeEach
  void resetServer() {
    httpsServer.reset();
  }

  @Autowired
  @Qualifier("mtlsReactiveWebClient")
  private WebClient mtlsReactiveWebClient;

  @Autowired
  @Qualifier("mtlsReactiveClientProperties")
  private ClientProperties mtlsReactiveClientProperties;

  @Autowired
  @Qualifier("mtlsRestJdkRestTemplate")
  private RestTemplate mtlsRestJdkRestTemplate;

  @Autowired
  @Qualifier("mtlsRestApacheRestTemplate")
  private RestTemplate mtlsRestApacheRestTemplate;

  // --- Verify certificates are configured ---

  @Test
  void testMtlsClientProperties_haveCertificatesConfigured() {
    assertThat(mtlsReactiveClientProperties.getCertificates()).isNotNull();
    assertThat(mtlsReactiveClientProperties.getCertificates().getKeyStore()).isNotNull();
    assertThat(mtlsReactiveClientProperties.getCertificates().getTrustStore()).isNotNull();
  }

  // --- Positive tests: mTLS-configured clients over HTTPS ---

  @Test
  void testReactiveClient_withCertificates_httpsGetSucceeds() {
    mockGet("/reactive-get", "reactive mTLS OK");

    StepVerifier.create(
            mtlsReactiveWebClient.get()
                .uri(httpsBaseUrl + "/reactive-get")
                .retrieve()
                .bodyToMono(String.class))
        .expectNext("reactive mTLS OK")
        .verifyComplete();
  }

  @Test
  void testReactiveClient_withCertificates_httpsPostSucceeds() {
    mockPost("/reactive-post", "reactive post OK");

    StepVerifier.create(
            mtlsReactiveWebClient.post()
                .uri(httpsBaseUrl + "/reactive-post")
                .bodyValue("{\"key\":\"value\"}")
                .retrieve()
                .bodyToMono(String.class))
        .expectNext("reactive post OK")
        .verifyComplete();
  }

  @Test
  void testRestJdkClient_withCertificates_httpsGetSucceeds() {
    mockGet("/rest-jdk-get", "jdk mTLS OK");

    String result = mtlsRestJdkRestTemplate.getForObject(
        httpsBaseUrl + "/rest-jdk-get", String.class);
    assertThat(result).isEqualTo("jdk mTLS OK");
  }

  @Test
  void testRestJdkClient_withCertificates_httpsPostSucceeds() {
    mockPost("/rest-jdk-post", "jdk post OK");

    String result = mtlsRestJdkRestTemplate.postForObject(
        httpsBaseUrl + "/rest-jdk-post", "{\"key\":\"value\"}", String.class);
    assertThat(result).isEqualTo("jdk post OK");
  }

  @Test
  void testRestApacheClient_withCertificates_httpsGetSucceeds() {
    mockGet("/rest-apache-get", "apache mTLS OK");

    String result = mtlsRestApacheRestTemplate.getForObject(
        httpsBaseUrl + "/rest-apache-get", String.class);
    assertThat(result).isEqualTo("apache mTLS OK");
  }

  @Test
  void testRestApacheClient_withCertificates_httpsPostSucceeds() {
    mockPost("/rest-apache-post", "apache post OK");

    String result = mtlsRestApacheRestTemplate.postForObject(
        httpsBaseUrl + "/rest-apache-post", "{\"key\":\"value\"}", String.class);
    assertThat(result).isEqualTo("apache post OK");
  }

  // --- Negative tests: clients without certificates fail over HTTPS ---

  @Test
  void testReactiveClient_withoutCertificates_httpsGetFails() {
    mockGet("/should-not-reach", "unreachable");

    HttpClient plainHttpClient = HttpClient.create();
    WebClient noSslClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(plainHttpClient))
        .build();

    StepVerifier.create(
            noSslClient.get()
                .uri(httpsBaseUrl + "/should-not-reach")
                .retrieve()
                .bodyToMono(String.class))
        .expectError(WebClientRequestException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void testRestClient_withoutCertificates_httpsGetFails() {
    mockGet("/should-not-reach", "unreachable");

    RestTemplate plainRestTemplate = new RestTemplate();

    assertThrows(ResourceAccessException.class, () ->
        plainRestTemplate.getForObject(httpsBaseUrl + "/should-not-reach", String.class));
  }

  // --- Helpers ---

  private void mockGet(String path, String responseBody) {
    httpsServer
        .when(request().withMethod("GET").withPath(path))
        .respond(response().withBody(responseBody).withStatusCode(200));
  }

  private void mockPost(String path, String responseBody) {
    httpsServer
        .when(request().withMethod("POST").withPath(path))
        .respond(response().withBody(responseBody).withStatusCode(200));
  }
}
