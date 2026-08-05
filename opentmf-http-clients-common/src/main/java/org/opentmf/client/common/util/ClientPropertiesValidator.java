package org.opentmf.client.common.util;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.model.ResilienceProperties;
import org.springframework.util.StringUtils;

/**
 * Public, side-effect-free validation of a (possibly programmatically built)
 * {@link ClientProperties}, returning ALL findings instead of failing fast — suitable for an
 * SRE "test connection" checklist. It performs no I/O: base64 material is decoded, but no
 * keystore is opened and no endpoint is contacted.
 */
@UtilityClass
public class ClientPropertiesValidator {

  private static final String MUST_BE_POSITIVE = "must be positive";
  private static final String MUST_NOT_BE_BLANK = "must not be blank";
  private static final String MAX_CONNECTIONS_PER_ROUTE = "max-connections-per-route";

  /**
   * One validation finding: the offending configuration {@code field} (relative to the client's
   * prefix, e.g. {@code bearer-auth.token-url}) and a human-readable {@code message}.
   */
  public record Finding(String field, String message) {

    @Override
    public String toString() {
      return field + ": " + message;
    }
  }

  /**
   * Validates the given properties as they would behave for the given resolved client type
   * (needed for the per-type findings, e.g. the JDK max-connections contract). Returns an empty
   * list when the configuration is sound.
   */
  public static List<Finding> validate(ClientType clientType, ClientProperties properties) {
    List<Finding> findings = new ArrayList<>();
    validateBaseUrl(properties, findings);
    validateConnectionSettings(clientType, properties, findings);
    validateAuth(properties, findings);
    validateCertificates(properties, findings);
    validateProxy(properties, findings);
    validateResilience(clientType, properties.getResilience(), findings);
    return List.copyOf(findings);
  }

  private static void validateBaseUrl(ClientProperties properties, List<Finding> findings) {
    var baseUrl = properties.getBaseUrl();
    if (!StringUtils.hasText(baseUrl)) {
      return;
    }
    try {
      if (!URI.create(baseUrl).isAbsolute()) {
        findings.add(new Finding("base-url", "must be an absolute URL: " + baseUrl));
      }
    } catch (IllegalArgumentException e) {
      findings.add(new Finding("base-url", "is not a valid URI: " + baseUrl));
    }
  }

  private static void validateConnectionSettings(ClientType clientType,
      ClientProperties properties, List<Finding> findings) {
    if (properties.getMaxConnections() <= 0) {
      findings.add(new Finding("max-connections", MUST_BE_POSITIVE));
    }
    var perRoute = properties.getMaxConnectionsPerRoute();
    if (perRoute != null) {
      if (perRoute <= 0) {
        findings.add(new Finding(MAX_CONNECTIONS_PER_ROUTE, "must be positive when set"));
      } else if (perRoute > properties.getMaxConnections()) {
        findings.add(new Finding(MAX_CONNECTIONS_PER_ROUTE,
            "exceeds max-connections (" + properties.getMaxConnections() + ") and can never be reached"));
      }
      if (clientType != ClientType.APACHE) {
        findings.add(new Finding(MAX_CONNECTIONS_PER_ROUTE,
            "is only honoured by client-type apache; it is ignored on " + clientType));
      }
    }
    if (clientType == ClientType.JDK
        && properties.getResilience().getBulkhead().getMaxConcurrentCalls() <= 0) {
      findings.add(new Finding("max-connections",
          "cannot be enforced on the JDK client type (no pool-size API). Use client-type apache,"
              + " or set resilience.bulkhead.max-concurrent-calls to enforce a concurrency"
              + " contract."));
    }
    requirePositive(properties.getRequestTimeout(), "request-timeout", findings);
    requirePositive(properties.getResponseTimeout(), "response-timeout", findings);
    requirePositive(properties.getConnectionIdleTimeout(), "connection-idle-timeout", findings);
    requirePositive(properties.getRetryWaitDuration(), "retry-wait-duration", findings);
    requirePositive(properties.getMaxRetryAfter(), "max-retry-after", findings);
    if (properties.getNumRetries() < 0) {
      findings.add(new Finding("num-retries", "must be zero or positive"));
    }
    if (!StringUtils.hasText(properties.getSslProtocol())) {
      findings.add(new Finding("ssl-protocol", MUST_NOT_BE_BLANK));
    }
  }

  private static void validateAuth(ClientProperties properties, List<Finding> findings) {
    var basic = properties.getBasicAuth();
    var bearer = properties.getBearerAuth();
    if (basic != null && bearer != null) {
      findings.add(new Finding("basic-auth", "cannot be combined with bearer-auth"));
    }
    if (basic != null) {
      if (!StringUtils.hasText(basic.getUsername())) {
        findings.add(new Finding("basic-auth.username", MUST_NOT_BE_BLANK));
      }
      if (!StringUtils.hasText(basic.getPassword())) {
        findings.add(new Finding("basic-auth.password", MUST_NOT_BE_BLANK));
      }
    }
    if (bearer != null && !bearer.isUseMock()) {
      validateBearerAuth(bearer, findings);
    }
  }

  private static void validateBearerAuth(BearerAuthConfig bearer, List<Finding> findings) {
    if (bearer.getTokenUrl() == null) {
      findings.add(new Finding("bearer-auth.token-url", "is required"));
    }
    if (bearer.getFormData() == null || bearer.getFormData().isEmpty()) {
      findings.add(new Finding("bearer-auth.form-data",
          "must contain at least one entry (e.g. grant_type)"));
    }
    if (!StringUtils.hasText(bearer.getTokenField())) {
      findings.add(new Finding("bearer-auth.token-field", MUST_NOT_BE_BLANK));
    }
    if (!StringUtils.hasText(bearer.getExpiresInField())) {
      findings.add(new Finding("bearer-auth.expires-in-field", MUST_NOT_BE_BLANK));
    }
    if (bearer.getFallbackExpiresInSeconds() <= 0) {
      findings.add(new Finding("bearer-auth.fallback-expires-in-seconds", MUST_BE_POSITIVE));
    }
    if (bearer.getCacheSafetyFactor() < 0.1 || bearer.getCacheSafetyFactor() > 0.99) {
      findings.add(new Finding("bearer-auth.cache-safety-factor",
          "must be between 0.1 and 0.99"));
    }
  }

  private static void validateCertificates(ClientProperties properties, List<Finding> findings) {
    var certificates = properties.getCertificates();
    if (certificates == null) {
      return;
    }
    var keyStore = certificates.getKeyStore();
    if (keyStore == null) {
      findings.add(new Finding("certificates.key-store",
          "is required when certificates are configured"));
    } else {
      requireBase64("certificates.key-store.base64-jks", keyStore.getBase64Jks(), findings);
      if (!StringUtils.hasText(keyStore.getPkPassword())) {
        findings.add(new Finding("certificates.key-store.pk-password", MUST_NOT_BE_BLANK));
      }
    }
    var trustStore = certificates.getTrustStore();
    if (trustStore != null) {
      requireBase64("certificates.trust-store.base64-jks", trustStore.getBase64Jks(), findings);
    }
  }

  private static void validateProxy(ClientProperties properties, List<Finding> findings) {
    var proxy = properties.getProxyConfig();
    if (proxy == null) {
      return;
    }
    if (!StringUtils.hasText(proxy.getProxyHost())) {
      findings.add(new Finding("proxy-config.proxy-host", MUST_NOT_BE_BLANK));
    }
    if (proxy.getProxyPort() <= 0 || proxy.getProxyPort() > 65535) {
      findings.add(new Finding("proxy-config.proxy-port", "must be between 1 and 65535"));
    }
  }

  private static void validateResilience(ClientType clientType, ResilienceProperties resilience,
      List<Finding> findings) {
    if (!resilience.isEnabled()) {
      return;
    }
    var circuitBreaker = resilience.getCircuitBreaker();
    requireRate(circuitBreaker.getFailureRateThreshold(),
        "resilience.circuit-breaker.failure-rate-threshold", findings);
    requireRate(circuitBreaker.getSlowCallRateThreshold(),
        "resilience.circuit-breaker.slow-call-rate-threshold", findings);
    if (circuitBreaker.getSlidingWindowSize() <= 0) {
      findings.add(new Finding("resilience.circuit-breaker.sliding-window-size",
          MUST_BE_POSITIVE));
    }
    if (circuitBreaker.getMinimumNumberOfCalls() <= 0) {
      findings.add(new Finding("resilience.circuit-breaker.minimum-number-of-calls",
          MUST_BE_POSITIVE));
    }
    if (circuitBreaker.getPermittedCallsInHalfOpen() <= 0) {
      findings.add(new Finding("resilience.circuit-breaker.permitted-calls-in-half-open",
          MUST_BE_POSITIVE));
    }
    requirePositive(circuitBreaker.getWaitDurationInOpenState(),
        "resilience.circuit-breaker.wait-duration-in-open-state", findings);
    for (Integer statusCode : circuitBreaker.getRecordStatusCodes()) {
      if (statusCode == null || statusCode < 100 || statusCode > 599) {
        findings.add(new Finding("resilience.circuit-breaker.record-status-codes",
            "contains an invalid HTTP status: " + statusCode));
      }
    }
    if (resilience.getBulkhead().getMaxConcurrentCalls() < 0) {
      findings.add(new Finding("resilience.bulkhead.max-concurrent-calls",
          "must be zero (disabled) or positive"));
    }
    var timeout = resilience.getTimeLimiter().getTimeoutDuration();
    if (timeout != null) {
      requirePositive(timeout, "resilience.time-limiter.timeout-duration", findings);
      if (clientType != ClientType.NETTY) {
        findings.add(new Finding("resilience.time-limiter.timeout-duration",
            "is only applied to client-type netty; it is ignored on " + clientType));
      }
    }
  }

  private static void requirePositive(@Nullable Duration duration, String field,
      List<Finding> findings) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      findings.add(new Finding(field, "must be a positive duration"));
    }
  }

  private static void requireRate(float rate, String field, List<Finding> findings) {
    if (rate < 1 || rate > 100) {
      findings.add(new Finding(field, "must be between 1 and 100"));
    }
  }

  private static void requireBase64(String field, @Nullable String value,
      List<Finding> findings) {
    if (!StringUtils.hasText(value)) {
      findings.add(new Finding(field, MUST_NOT_BE_BLANK));
      return;
    }
    try {
      Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      findings.add(new Finding(field, "is not valid base64"));
    }
  }
}
