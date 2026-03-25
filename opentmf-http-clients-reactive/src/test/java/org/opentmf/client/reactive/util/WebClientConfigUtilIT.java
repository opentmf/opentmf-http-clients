package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import reactor.test.StepVerifier;

class WebClientConfigUtilIT {

  private static ClientAndServer mockServer;
  private static String baseUrl;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    baseUrl = "http://localhost:" + mockServer.getLocalPort();
  }

  @AfterAll
  static void stopMockServer() {
    if (mockServer != null && mockServer.isRunning()) {
      mockServer.stop();
    }
  }

  @BeforeEach
  void resetServer() {
    mockServer.reset();
  }

  @Test
  void httpClient_andCreateWebClient_minimalConfig() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/hello"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"world\""));

    var props = minimalProperties();
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "test", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri(baseUrl + "/api/hello")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"world\"")
        .verifyComplete();
  }

  @Test
  void httpClient_withBaseUrl() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/items"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"ok\""));

    var props = minimalProperties();
    props.setBaseUrl(baseUrl);
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "baseUrlTest", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri("/items")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"ok\"")
        .verifyComplete();
  }

  @Test
  void httpClient_withFixedHeaders() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/headers")
                    .withHeader("X-Custom", "value1"),
            Times.once())
        .respond(response().withStatusCode(200).withBody("\"headers ok\""));

    var props = minimalProperties();
    props.setFixedHeaders(java.util.Map.of("X-Custom", "value1"));
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "headerTest", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri(baseUrl + "/api/headers")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"headers ok\"")
        .verifyComplete();
  }

  @Test
  void httpClient_loggingDisabled() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/nolog"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"nolog\""));

    var props = minimalProperties();
    props.setLoggingEnabled(false);
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "noLog", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri(baseUrl + "/api/nolog")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"nolog\"")
        .verifyComplete();
  }

  @Test
  void httpClient_followRedirectsDisabled() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/redirect"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"direct\""));

    var props = minimalProperties();
    props.setFollowRedirects(false);
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "noRedirect", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri(baseUrl + "/api/redirect")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"direct\"")
        .verifyComplete();
  }

  @Test
  void httpClient_withProxyConfig() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/proxied"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"proxied\""));

    var props = minimalProperties();
    var proxy = new ClientProperties.ProxyConfig();
    proxy.setProxyHost("localhost");
    proxy.setProxyPort(mockServer.getLocalPort());
    proxy.setNonProxyHosts(List.of("10.0.0.1"));
    props.setProxyConfig(proxy);

    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "proxyTest", props);
    assertThat(httpClient).isNotNull();
  }

  @Test
  void errorWrappingFilter_404_throwsNotFoundException() {
    mockServer.when(request().withMethod("GET").withPath("/api/missing"), Times.once())
        .respond(response().withStatusCode(404).withBody("{\"detail\":\"not found\"}"));

    var props = minimalProperties();
    var logbook = Logbook.create();
    try {
      var httpClient = WebClientConfigUtil.httpClient(logbook, "err404", props);
      var webClient = WebClientConfigUtil.createWebClient(
          WebClient.builder(), httpClient, props);

      StepVerifier.create(webClient.get().uri(baseUrl + "/api/missing")
              .retrieve().bodyToMono(String.class))
          .expectError(OpenTmfClientNotFoundException.class)
          .verify();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void errorWrappingFilter_500_throwsClientResponseException() {
    mockServer.when(request().withMethod("GET").withPath("/api/error"), Times.once())
        .respond(response().withStatusCode(500).withBody("{\"message\":\"internal\"}"));

    var props = minimalProperties();
    var logbook = Logbook.create();
    try {
      var httpClient = WebClientConfigUtil.httpClient(logbook, "err500", props);
      var webClient = WebClientConfigUtil.createWebClient(
          WebClient.builder(), httpClient, props);

      StepVerifier.create(webClient.get().uri(baseUrl + "/api/error")
              .retrieve().bodyToMono(String.class))
          .expectErrorSatisfies(ex -> {
            assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
            assertThat(ex).isNotInstanceOf(OpenTmfClientNotFoundException.class);
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getRawStatusCode()).isEqualTo(500);
          })
          .verify();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void errorWrappingFilter_emptyBody() {
    mockServer.when(request().withMethod("GET").withPath("/api/empty-error"), Times.once())
        .respond(response().withStatusCode(502));

    var props = minimalProperties();
    var logbook = Logbook.create();
    try {
      var httpClient = WebClientConfigUtil.httpClient(logbook, "errEmpty", props);
      var webClient = WebClientConfigUtil.createWebClient(
          WebClient.builder(), httpClient, props);

      StepVerifier.create(webClient.get().uri(baseUrl + "/api/empty-error")
              .retrieve().bodyToMono(String.class))
          .expectErrorSatisfies(ex -> {
            assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getRawStatusCode()).isEqualTo(502);
          })
          .verify();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void httpClient_withCustomSslProtocol() throws Exception {
    mockServer.when(request().withMethod("GET").withPath("/api/ssl"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"ssl-ok\""));

    var props = minimalProperties();
    props.setSslProtocol("TLSv1.3");
    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "sslProto", props);
    var webClient = WebClientConfigUtil.createWebClient(
        WebClient.builder(), httpClient, props);

    StepVerifier.create(webClient.get().uri(baseUrl + "/api/ssl")
            .retrieve().bodyToMono(String.class))
        .expectNext("\"ssl-ok\"")
        .verifyComplete();
  }

  @Test
  void httpClient_withMtlsCertificates() throws Exception {
    var keyPairGen = KeyPairGenerator.getInstance("RSA");
    keyPairGen.initialize(2048);
    var keyPair = keyPairGen.generateKeyPair();

    var issuer = new X500Name("CN=TestCA");
    var certBuilder = new JcaX509v3CertificateBuilder(
        issuer, BigInteger.valueOf(1),
        Date.from(Instant.now().minus(Duration.ofDays(1))),
        Date.from(Instant.now().plus(Duration.ofDays(365))),
        issuer, keyPair.getPublic());
    var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

    String ksPassword = "test-ks-pwd";
    String pkPassword = "test-pk-pwd";
    var keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, ksPassword.toCharArray());
    keyStore.setKeyEntry("key", keyPair.getPrivate(), pkPassword.toCharArray(),
        new Certificate[]{cert});
    var ksOut = new ByteArrayOutputStream();
    keyStore.store(ksOut, ksPassword.toCharArray());
    String ksBase64 = Base64.getEncoder().encodeToString(ksOut.toByteArray());

    String tsPassword = "test-ts-pwd";
    var trustStore = KeyStore.getInstance("JKS");
    trustStore.load(null, tsPassword.toCharArray());
    trustStore.setCertificateEntry("ca", cert);
    var tsOut = new ByteArrayOutputStream();
    trustStore.store(tsOut, tsPassword.toCharArray());
    String tsBase64 = Base64.getEncoder().encodeToString(tsOut.toByteArray());

    var props = minimalProperties();
    var certs = new ClientProperties.Certificates();
    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks(ksBase64);
    ks.setPassword(ksPassword);
    ks.setPkPassword(pkPassword);
    certs.setKeyStore(ks);
    var ts = new ClientProperties.Certificates.TrustStore();
    ts.setBase64Jks(tsBase64);
    ts.setPassword(tsPassword);
    certs.setTrustStore(ts);
    props.setCertificates(certs);

    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "mtlsTest", props);
    assertThat(httpClient).isNotNull();
  }

  @Test
  void httpClient_withMtlsCertificates_withoutTrustStore() throws Exception {
    var keyPairGen = KeyPairGenerator.getInstance("RSA");
    keyPairGen.initialize(2048);
    var keyPair = keyPairGen.generateKeyPair();

    var issuer = new X500Name("CN=TestCA");
    var certBuilder = new JcaX509v3CertificateBuilder(
        issuer, BigInteger.valueOf(1),
        Date.from(Instant.now().minus(Duration.ofDays(1))),
        Date.from(Instant.now().plus(Duration.ofDays(365))),
        issuer, keyPair.getPublic());
    var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

    String ksPassword = "pwd";
    String pkPassword = "pkpwd";
    var keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, ksPassword.toCharArray());
    keyStore.setKeyEntry("key", keyPair.getPrivate(), pkPassword.toCharArray(),
        new Certificate[]{cert});
    var ksOut = new ByteArrayOutputStream();
    keyStore.store(ksOut, ksPassword.toCharArray());
    String ksBase64 = Base64.getEncoder().encodeToString(ksOut.toByteArray());

    var props = minimalProperties();
    var certs = new ClientProperties.Certificates();
    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks(ksBase64);
    ks.setPassword(ksPassword);
    ks.setPkPassword(pkPassword);
    certs.setKeyStore(ks);
    props.setCertificates(certs);

    var logbook = Logbook.create();
    var httpClient = WebClientConfigUtil.httpClient(logbook, "mtlsNoTs", props);
    assertThat(httpClient).isNotNull();
  }

  @Test
  void httpClient_withInvalidCertificates_throws() {
    var props = minimalProperties();
    var certs = new ClientProperties.Certificates();
    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks(Base64.getEncoder().encodeToString("invalid".getBytes()));
    ks.setPassword("pwd");
    ks.setPkPassword("pk");
    certs.setKeyStore(ks);
    props.setCertificates(certs);

    var logbook = Logbook.create();
    assertThatThrownBy(() -> WebClientConfigUtil.httpClient(logbook, "badCert", props))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2-Way TLS");
  }

  private static ClientProperties minimalProperties() {
    var props = new ClientProperties();
    props.setMaxConnections(10);
    return props;
  }
}
