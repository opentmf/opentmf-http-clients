package org.opentmf.client.starter.rest;

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
import java.util.Map;
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
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.starter.ApachePoolMeters;
import org.springframework.beans.factory.ObjectProvider;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

class ApacheRestTemplateFactoryTest {

  private static ClientAndServer mockServer;
  private static final RestLogbookSupport LOGBOOK_SUPPORT =
      new RestLogbookSupport(new LogbookClientHttpRequestInterceptor(Logbook.builder().build()));
  private static final ObjectProvider<RestLogbookSupport> SUPPORT_PROVIDER = new ObjectProvider<>() {
    @Override public RestLogbookSupport getObject() { return LOGBOOK_SUPPORT; }
    @Override public RestLogbookSupport getIfAvailable() { return LOGBOOK_SUPPORT; }
  };
  private static final ObjectProvider<ResilienceRegistries> NO_RESILIENCE = new ObjectProvider<>() {
    @Override public ResilienceRegistries getObject() { return null; }
    @Override public ResilienceRegistries getIfAvailable() { return null; }
  };
  private static final ObjectProvider<ApachePoolMeters> NO_POOL_METERS = new ObjectProvider<>() {
    @Override public ApachePoolMeters getObject() { return null; }
    @Override public ApachePoolMeters getIfAvailable() { return null; }
  };
  private final ApacheRestTemplateFactory factory = new ApacheRestTemplateFactory(SUPPORT_PROVIDER, NO_RESILIENCE, NO_POOL_METERS);

  @BeforeAll
  static void startServer() {
    mockServer = ClientAndServer.startClientAndServer();
  }

  @AfterAll
  static void stopServer() {
    if (mockServer != null) mockServer.stop();
  }

  @BeforeEach
  void reset() {
    mockServer.reset();
  }

  @Test
  void create_minimalConfig() {
    mockServer.when(request().withMethod("GET").withPath("/hello"), Times.once())
        .respond(response().withStatusCode(200).withBody("world"));

    var props = new ClientProperties();
    props.setBaseUrl("http://localhost:" + mockServer.getLocalPort());
    var restTemplate = factory.create("apacheTest", props);

    var result = restTemplate.getForObject("/hello", String.class);
    assertThat(result).isEqualTo("world");
  }

  @Test
  void create_withProxy() {
    var props = new ClientProperties();
    var proxy = new ClientProperties.ProxyConfig();
    proxy.setProxyHost("localhost");
    proxy.setProxyPort(mockServer.getLocalPort());
    props.setProxyConfig(proxy);

    var restTemplate = factory.create("apacheProxy", props);
    assertThat(restTemplate).isNotNull();
  }

  @Test
  void create_withRedirectsDisabled() {
    var props = new ClientProperties();
    props.setFollowRedirects(false);
    props.setBaseUrl("http://localhost:" + mockServer.getLocalPort());

    mockServer.when(request().withMethod("GET").withPath("/data"), Times.once())
        .respond(response().withStatusCode(200).withBody("ok"));

    var restTemplate = factory.create("noRedirect", props);
    var result = restTemplate.getForObject("/data", String.class);
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void create_withInvalidCertificates_throws() {
    var props = new ClientProperties();
    var certs = new ClientProperties.Certificates();
    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks(Base64.getEncoder().encodeToString("bad".getBytes()));
    ks.setPassword("pwd");
    ks.setPkPassword("pk");
    certs.setKeyStore(ks);
    props.setCertificates(certs);

    assertThatThrownBy(() -> factory.create("badCert", props))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Apache RestTemplate");
  }

  @Test
  void create_withValidCertificates() throws Exception {
    var props = new ClientProperties();
    props.setCertificates(generateCertificates());
    var restTemplate = factory.create("sslApache", props);
    assertThat(restTemplate).isNotNull();
  }

  @Test
  void create_withCertificatesAndTrustStore() throws Exception {
    var props = new ClientProperties();
    props.setCertificates(generateCertificatesWithTrustStore());
    var restTemplate = factory.create("sslApacheTs", props);
    assertThat(restTemplate).isNotNull();
  }

  @Test
  void create_withMaxConnectionsPerRoute() {
    var props = new ClientProperties();
    props.setMaxConnectionsPerRoute(5);
    props.setBaseUrl("http://localhost:" + mockServer.getLocalPort());

    mockServer.when(request().withMethod("GET").withPath("/route"), Times.once())
        .respond(response().withStatusCode(200).withBody("routed"));

    var restTemplate = factory.create("routeApache", props);
    assertThat(restTemplate.getForObject("/route", String.class)).isEqualTo("routed");
  }

  @Test
  void create_withLoggingEnabled_addsLogbookInterceptor() {
    var props = new ClientProperties();
    props.setLoggingEnabled(true);
    var restTemplate = factory.create("logbook", props);
    assertThat(restTemplate.getInterceptors()).anyMatch(
        i -> i.getClass().getSimpleName().contains("Logbook"));
  }

  @Test
  void create_withLoggingDisabled_noLogbookInterceptor() {
    var props = new ClientProperties();
    props.setLoggingEnabled(false);
    var restTemplate = factory.create("noLogbook", props);
    assertThat(restTemplate.getInterceptors()).noneMatch(
        i -> i.getClass().getSimpleName().contains("Logbook"));
  }

  @Test
  void create_withNoLogbookBean_noLogbookInterceptor() {
    ObjectProvider<RestLogbookSupport> emptyProvider = new ObjectProvider<>() {
      @Override public RestLogbookSupport getObject() { return null; }
      @Override public RestLogbookSupport getIfAvailable() { return null; }
    };
    var localFactory = new ApacheRestTemplateFactory(emptyProvider, NO_RESILIENCE, NO_POOL_METERS);
    var props = new ClientProperties();
    props.setLoggingEnabled(true);
    var restTemplate = localFactory.create("noBean", props);
    assertThat(restTemplate.getInterceptors()).noneMatch(
        i -> i.getClass().getSimpleName().contains("Logbook"));
  }

  @Test
  void create_withFixedHeaders_addsHeadersToRequest() {
    mockServer.when(request().withMethod("GET").withPath("/headers")
        .withHeader("X-Custom", "myvalue"), Times.once())
        .respond(response().withStatusCode(200).withBody("headered"));

    var props = new ClientProperties();
    props.setBaseUrl("http://localhost:" + mockServer.getLocalPort());
    props.setFixedHeaders(Map.of("X-Custom", "myvalue"));

    var restTemplate = factory.create("fixedHdr", props);
    assertThat(restTemplate.getForObject("/headers", String.class)).isEqualTo("headered");
  }

  @Test
  void create_withCompressionDisabled() {
    mockServer.when(request().withMethod("GET").withPath("/nocomp"), Times.once())
        .respond(response().withStatusCode(200).withBody("uncompressed"));

    var props = new ClientProperties();
    props.setCompressionEnabled(false);
    props.setBaseUrl("http://localhost:" + mockServer.getLocalPort());

    var restTemplate = factory.create("noComp", props);
    assertThat(restTemplate.getForObject("/nocomp", String.class)).isEqualTo("uncompressed");
  }

  private static ClientProperties.Certificates generateCertificates() throws Exception {
    var keyPairGen = KeyPairGenerator.getInstance("RSA");
    keyPairGen.initialize(2048);
    var keyPair = keyPairGen.generateKeyPair();

    var issuer = new X500Name("CN=TestCA");
    var certBuilder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE,
        Date.from(Instant.now().minus(Duration.ofDays(1))),
        Date.from(Instant.now().plus(Duration.ofDays(365))),
        issuer, keyPair.getPublic());
    var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

    String ksPassword = "kspwd";
    String pkPassword = "pkpwd";
    var keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, ksPassword.toCharArray());
    keyStore.setKeyEntry("key", keyPair.getPrivate(), pkPassword.toCharArray(), new Certificate[]{cert});
    var ksOut = new ByteArrayOutputStream();
    keyStore.store(ksOut, ksPassword.toCharArray());

    var certs = new ClientProperties.Certificates();
    var ks = new ClientProperties.Certificates.KeyStore();
    ks.setBase64Jks(Base64.getEncoder().encodeToString(ksOut.toByteArray()));
    ks.setPassword(ksPassword);
    ks.setPkPassword(pkPassword);
    certs.setKeyStore(ks);
    return certs;
  }

  private static ClientProperties.Certificates generateCertificatesWithTrustStore() throws Exception {
    var certs = generateCertificates();

    var keyPairGen = KeyPairGenerator.getInstance("RSA");
    keyPairGen.initialize(2048);
    var keyPair = keyPairGen.generateKeyPair();
    var issuer = new X500Name("CN=TrustCA");
    var certBuilder = new JcaX509v3CertificateBuilder(issuer, BigInteger.TWO,
        Date.from(Instant.now().minus(Duration.ofDays(1))),
        Date.from(Instant.now().plus(Duration.ofDays(365))),
        issuer, keyPair.getPublic());
    var signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));

    String tsPassword = "tspwd";
    var trustStore = KeyStore.getInstance("JKS");
    trustStore.load(null, tsPassword.toCharArray());
    trustStore.setCertificateEntry("ca", cert);
    var tsOut = new ByteArrayOutputStream();
    trustStore.store(tsOut, tsPassword.toCharArray());

    var ts = new ClientProperties.Certificates.TrustStore();
    ts.setBase64Jks(Base64.getEncoder().encodeToString(tsOut.toByteArray()));
    ts.setPassword(tsPassword);
    certs.setTrustStore(ts);
    return certs;
  }
}
