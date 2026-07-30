package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BasicAuthConfig;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.util.ClientPropertiesValidator.Finding;

class ClientPropertiesValidatorTest {

  private static List<String> fields(List<Finding> findings) {
    return findings.stream().map(Finding::field).toList();
  }

  private static ClientProperties apacheDefaults() {
    var properties = new ClientProperties();
    properties.setBaseUrl("https://api.example.com");
    return properties;
  }

  @Test
  void soundApacheConfiguration_hasNoFindings() {
    assertThat(ClientPropertiesValidator.validate(ClientType.APACHE, apacheDefaults())).isEmpty();
  }

  @Test
  void jdkWithoutBulkhead_flagsUnenforceableMaxConnections() {
    var findings = ClientPropertiesValidator.validate(ClientType.JDK, apacheDefaults());
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).field()).isEqualTo("max-connections");
    assertThat(findings.get(0).message()).contains("client-type apache")
        .contains("resilience.bulkhead.max-concurrent-calls");
  }

  @Test
  void jdkWithBulkhead_hasNoMaxConnectionsFinding() {
    var properties = apacheDefaults();
    properties.getResilience().getBulkhead().setMaxConcurrentCalls(20);
    assertThat(ClientPropertiesValidator.validate(ClientType.JDK, properties)).isEmpty();
  }

  @Test
  void baseUrl_relativeOrMalformed_isFlagged() {
    var relative = apacheDefaults();
    relative.setBaseUrl("/just/a/path");
    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, relative)))
        .contains("base-url");

    var malformed = apacheDefaults();
    malformed.setBaseUrl("ht tp://broken url");
    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, malformed)))
        .contains("base-url");
  }

  @Test
  void connectionSettings_invalidValues_allReported() {
    var properties = apacheDefaults();
    properties.setMaxConnections(0);
    properties.setRequestTimeout(Duration.ZERO);
    properties.setResponseTimeout(Duration.ofSeconds(-1));
    properties.setConnectionIdleTimeout(null);
    properties.setRetryWaitDuration(Duration.ZERO);
    properties.setNumRetries(-1);
    properties.setSslProtocol(" ");

    var findings = ClientPropertiesValidator.validate(ClientType.APACHE, properties);

    assertThat(fields(findings)).contains("max-connections", "request-timeout",
        "response-timeout", "connection-idle-timeout", "retry-wait-duration", "num-retries",
        "ssl-protocol");
  }

  @Test
  void maxConnectionsPerRoute_crossChecks() {
    var tooHigh = apacheDefaults();
    tooHigh.setMaxConnectionsPerRoute(500);
    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, tooHigh)))
        .contains("max-connections-per-route");

    var nonPositive = apacheDefaults();
    nonPositive.setMaxConnectionsPerRoute(0);
    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, nonPositive)))
        .contains("max-connections-per-route");

    var wrongType = apacheDefaults();
    wrongType.setMaxConnectionsPerRoute(50);
    wrongType.getResilience().getBulkhead().setMaxConcurrentCalls(10);
    assertThat(ClientPropertiesValidator.validate(ClientType.NETTY, wrongType))
        .anySatisfy(finding -> assertThat(finding.message()).contains("only honoured"));
  }

  @Test
  void bothAuthModes_isFlagged() {
    var properties = apacheDefaults();
    properties.setBasicAuth(new BasicAuthConfig("user", "pass", "US-ASCII"));
    var bearer = new BearerAuthConfig();
    bearer.setTokenUrl(URI.create("https://idp/token"));
    bearer.setFormData(Map.of("grant_type", "client_credentials"));
    properties.setBearerAuth(bearer);

    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, properties)))
        .contains("basic-auth");
  }

  @Test
  void basicAuth_blankCredentials_areFlagged() {
    var properties = apacheDefaults();
    properties.setBasicAuth(new BasicAuthConfig(" ", "", "US-ASCII"));

    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, properties)))
        .contains("basic-auth.username", "basic-auth.password");
  }

  @Test
  void bearerAuth_missingEssentials_allReported() {
    var properties = apacheDefaults();
    var bearer = new BearerAuthConfig();
    bearer.setTokenField("");
    bearer.setExpiresInField(" ");
    bearer.setFallbackExpiresInSeconds(0);
    bearer.setCacheSafetyFactor(1.5);
    properties.setBearerAuth(bearer);

    var findings = ClientPropertiesValidator.validate(ClientType.APACHE, properties);

    assertThat(fields(findings)).contains("bearer-auth.token-url", "bearer-auth.form-data",
        "bearer-auth.token-field", "bearer-auth.expires-in-field",
        "bearer-auth.fallback-expires-in-seconds", "bearer-auth.cache-safety-factor");
  }

  @Test
  void bearerAuth_mock_skipsEndpointChecks() {
    var properties = apacheDefaults();
    var bearer = new BearerAuthConfig();
    bearer.setUseMock(true);
    properties.setBearerAuth(bearer);

    assertThat(ClientPropertiesValidator.validate(ClientType.APACHE, properties)).isEmpty();
  }

  @Test
  void certificates_missingKeyStoreAndBadBase64_areFlagged() {
    var missingKeyStore = apacheDefaults();
    missingKeyStore.setCertificates(new ClientProperties.Certificates());
    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, missingKeyStore)))
        .contains("certificates.key-store");

    var badMaterial = apacheDefaults();
    var certificates = new ClientProperties.Certificates();
    var keyStore = new ClientProperties.Certificates.KeyStore();
    keyStore.setBase64Jks("not-valid-base64!!!");
    keyStore.setPkPassword(" ");
    certificates.setKeyStore(keyStore);
    var trustStore = new ClientProperties.Certificates.TrustStore();
    trustStore.setBase64Jks("");
    certificates.setTrustStore(trustStore);
    badMaterial.setCertificates(certificates);

    var findings = ClientPropertiesValidator.validate(ClientType.APACHE, badMaterial);
    assertThat(fields(findings)).contains("certificates.key-store.base64-jks",
        "certificates.key-store.pk-password", "certificates.trust-store.base64-jks");
  }

  @Test
  void certificates_validBase64_passes() {
    var properties = apacheDefaults();
    var certificates = new ClientProperties.Certificates();
    var keyStore = new ClientProperties.Certificates.KeyStore();
    keyStore.setBase64Jks(Base64.getEncoder().encodeToString("jks".getBytes()));
    keyStore.setPkPassword("secret");
    certificates.setKeyStore(keyStore);
    properties.setCertificates(certificates);

    assertThat(ClientPropertiesValidator.validate(ClientType.APACHE, properties)).isEmpty();
  }

  @Test
  void proxy_invalidHostAndPort_areFlagged() {
    var properties = apacheDefaults();
    var proxy = new ClientProperties.ProxyConfig();
    proxy.setProxyHost(" ");
    proxy.setProxyPort(0);
    properties.setProxyConfig(proxy);

    assertThat(fields(ClientPropertiesValidator.validate(ClientType.APACHE, properties)))
        .contains("proxy-config.proxy-host", "proxy-config.proxy-port");
  }

  @Test
  void resilience_disabled_isNotValidated() {
    var properties = apacheDefaults();
    properties.getResilience().getCircuitBreaker().setFailureRateThreshold(0);
    assertThat(ClientPropertiesValidator.validate(ClientType.APACHE, properties)).isEmpty();
  }

  @Test
  void resilience_enabled_invalidValues_allReported() {
    var properties = apacheDefaults();
    var resilience = properties.getResilience();
    resilience.setEnabled(true);
    var circuitBreaker = resilience.getCircuitBreaker();
    circuitBreaker.setFailureRateThreshold(0);
    circuitBreaker.setSlowCallRateThreshold(101);
    circuitBreaker.setSlidingWindowSize(0);
    circuitBreaker.setMinimumNumberOfCalls(0);
    circuitBreaker.setPermittedCallsInHalfOpen(0);
    circuitBreaker.setWaitDurationInOpenState(Duration.ZERO);
    circuitBreaker.setRecordStatusCodes(List.of(99, 503, 600));
    resilience.getBulkhead().setMaxConcurrentCalls(-1);
    resilience.getTimeLimiter().setTimeoutDuration(Duration.ZERO);

    var findings = ClientPropertiesValidator.validate(ClientType.APACHE, properties);

    assertThat(fields(findings)).contains(
        "resilience.circuit-breaker.failure-rate-threshold",
        "resilience.circuit-breaker.slow-call-rate-threshold",
        "resilience.circuit-breaker.sliding-window-size",
        "resilience.circuit-breaker.minimum-number-of-calls",
        "resilience.circuit-breaker.permitted-calls-in-half-open",
        "resilience.circuit-breaker.wait-duration-in-open-state",
        "resilience.circuit-breaker.record-status-codes",
        "resilience.bulkhead.max-concurrent-calls",
        "resilience.time-limiter.timeout-duration");
    assertThat(findings.stream()
        .filter(f -> f.field().equals("resilience.circuit-breaker.record-status-codes")))
        .hasSize(2);
  }

  @Test
  void resilience_timeLimiterOnSyncClient_isFlaggedAsIgnored() {
    var properties = apacheDefaults();
    properties.getResilience().setEnabled(true);
    properties.getResilience().getTimeLimiter().setTimeoutDuration(Duration.ofSeconds(5));

    assertThat(ClientPropertiesValidator.validate(ClientType.APACHE, properties))
        .anySatisfy(finding -> assertThat(finding.message()).contains("only applied"));
    assertThat(ClientPropertiesValidator.validate(ClientType.NETTY, properties)).isEmpty();
  }

  @Test
  void finding_toString_isReadable() {
    assertThat(new Finding("base-url", "must be absolute"))
        .hasToString("base-url: must be absolute");
  }
}
