package org.opentmf.client.starter.rest;

import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.resilience.ResilienceClientHttpRequestInterceptor;
import org.opentmf.client.rest.service.api.OutboundInterceptorContribution;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.util.FixedHeadersClientHttpRequestInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Shared plumbing for the {@link RestTemplateFactory} implementations: mTLS
 * {@link SSLContext} construction from base64-encoded JKS material, the application's
 * {@link OutboundInterceptorContribution}s, the fixed-headers interceptor (defaults only —
 * per-request headers win), optional Logbook wiring, and optional resilience4j decoration.
 */
@Slf4j
abstract class AbstractRestTemplateFactory implements RestTemplateFactory {

  private final RestLogbookSupport logbookSupport;
  private final ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider;
  private final @Nullable ObservationRegistry observationRegistry;
  private final ObjectProvider<OutboundInterceptorContribution> contributionsProvider;

  protected AbstractRestTemplateFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider,
      ObjectProvider<ObservationRegistry> observationRegistryProvider,
      ObjectProvider<OutboundInterceptorContribution> contributionsProvider) {
    this.logbookSupport = logbookSupportProvider.getIfAvailable();
    this.resilienceRegistriesProvider = resilienceRegistriesProvider;
    this.observationRegistry = observationRegistryProvider.getIfAvailable();
    this.contributionsProvider = contributionsProvider;
  }

  /**
   * Puts the application's {@link OutboundInterceptorContribution}s at the HEAD of the chain, in
   * their order, ahead of every interceptor of the library's own. Must be invoked before the
   * other interceptor-adding helpers. The {@code RestClient} built from the returned
   * {@code RestTemplate} copies the list, so it carries them too, and so do the token calls
   * that run through that {@code RestClient}. Looked up on every call, so clients built at
   * runtime see the same contributions. Without a contribution bean nothing is added.
   */
  protected void addContributedInterceptors(RestTemplate restTemplate) {
    contributionsProvider.orderedStream()
        .map(OutboundInterceptorContribution::interceptor)
        .forEach(restTemplate.getInterceptors()::add);
  }

  /**
   * Hands the application's {@link ObservationRegistry} to the {@code RestTemplate} so outbound
   * calls participate in Micrometer observation. When the consumer runs Micrometer-tracing, its
   * propagating handler then emits W3C trace context ({@code traceparent}) on every outbound
   * request; without a tracer the observation is a no-op. When no registry bean exists, the
   * template keeps its default no-op registry. The {@code RestClient} built from this template
   * inherits the registry.
   */
  protected void applyObservationRegistry(RestTemplate restTemplate) {
    if (observationRegistry != null) {
      restTemplate.setObservationRegistry(observationRegistry);
    }
  }

  protected SSLContext buildSslContext(ClientProperties properties)
      throws GeneralSecurityException, IOException {
    if (properties.getCertificates() == null) {
      return SSLContext.getDefault();
    }
    var certs = properties.getCertificates();

    var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    var keyCert = Base64.getDecoder().decode(certs.getKeyStore().getBase64Jks());
    var ksPassword = certs.getKeyStore().getPassword();
    keyStore.load(new ByteArrayInputStream(keyCert),
        ksPassword == null ? null : ksPassword.toCharArray());

    var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, certs.getKeyStore().getPkPassword().toCharArray());

    TrustManagerFactory tmf = null;
    if (certs.getTrustStore() != null) {
      var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      var trustCert = Base64.getDecoder().decode(certs.getTrustStore().getBase64Jks());
      var tsPassword = certs.getTrustStore().getPassword();
      trustStore.load(new ByteArrayInputStream(trustCert),
          tsPassword == null ? null : tsPassword.toCharArray());
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
    }

    var sslContext = SSLContext.getInstance(properties.getSslProtocol());
    sslContext.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);
    return sslContext;
  }

  /**
   * Adds the resilience4j interceptor as the OUTERMOST of the library's own interceptors when
   * {@code resilience.enabled} is true. Must be invoked right after
   * {@link #addContributedInterceptors} and before the other interceptor-adding helpers, so that
   * the bulkhead and circuit breaker wrap the whole downstream chain. The
   * {@code RestClient} built from the returned {@code RestTemplate} inherits the interceptor.
   */
  protected void addResilienceInterceptor(RestTemplate restTemplate, String clientId,
      ClientProperties properties) {
    var resilience = properties.getResilience();
    if (!resilience.isEnabled()) {
      return;
    }
    var registries = resilienceRegistriesProvider.getIfAvailable();
    if (registries == null) {
      throw new IllegalStateException("Client '" + clientId + "' has resilience.enabled: true, "
          + "but resilience4j is not on the classpath. Add io.github.resilience4j:"
          + "resilience4j-circuitbreaker and resilience4j-bulkhead, or disable resilience.");
    }
    var circuitBreaker = registries.circuitBreaker(clientId, resilience);
    var bulkhead = registries.bulkhead(clientId, resilience);
    restTemplate.getInterceptors().add(new ResilienceClientHttpRequestInterceptor(clientId,
        circuitBreaker, bulkhead,
        Set.copyOf(resilience.getCircuitBreaker().getRecordStatusCodes())));
    log.debug("Resilience decoration enabled for client '{}' (bulkhead: {})", clientId,
        bulkhead != null);
  }

  protected void addFixedHeadersInterceptor(RestTemplate restTemplate,
      ClientProperties properties) {
    if (!CollectionUtils.isEmpty(properties.getFixedHeaders())) {
      restTemplate.getInterceptors()
          .add(new FixedHeadersClientHttpRequestInterceptor(properties.getFixedHeaders()));
    }
  }

  protected void addLogbookInterceptor(RestTemplate restTemplate, ClientProperties properties) {
    if (properties.isLoggingEnabled()) {
      if (logbookSupport != null) {
        logbookSupport.addInterceptor(restTemplate);
      } else {
        log.warn("Client has logging-enabled: true, but no Logbook bean found. "
            + "Add org.zalando:logbook-spring to your classpath to enable HTTP logging.");
      }
    }
  }
}
