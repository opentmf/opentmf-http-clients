package org.opentmf.client.starter.rest;

import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
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
import org.opentmf.client.rest.service.api.RestClientFactory;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.util.FixedHeadersClientHttpRequestInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Shared plumbing for the {@link RestClientFactory} and {@link RestTemplateFactory}
 * implementations: mTLS {@link SSLContext} construction from base64-encoded JKS material, the
 * application's {@link OutboundInterceptorContribution}s, the fixed-headers interceptor (defaults
 * only — per-request headers win), optional Logbook wiring, and optional resilience4j
 * decoration. A backend builds its {@link SyncClientPipeline}; the {@code RestClient} and the
 * {@code RestTemplate} are both made from it.
 */
@Slf4j
@SuppressWarnings("deprecation") // implements RestTemplateFactory until it is removed
abstract class AbstractRestTemplateFactory implements RestClientFactory, RestTemplateFactory {

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
   * Builds the client's {@link SyncClientPipeline}: the backend's request factory and the
   * interceptors in their order, with the application's {@link ObservationRegistry}.
   *
   * @param shape what is being built, named in the failure's message
   * @throws IllegalArgumentException when the client cannot be built from the properties
   */
  abstract SyncClientPipeline pipeline(String clientId, ClientProperties properties,
      String shape);

  /** The client's pipeline, as the registrar builds it once for both of its beans. */
  SyncClientPipeline pipeline(String clientId, ClientProperties properties) {
    return pipeline(clientId, properties, "client");
  }

  /**
   * The pipeline over the given request factory and interceptors. The application's
   * {@link ObservationRegistry} makes outbound calls participate in Micrometer observation: when
   * the consumer runs Micrometer-tracing, its propagating handler then emits W3C trace context
   * ({@code traceparent}) on every outbound request; without a tracer the observation is a
   * no-op, and without a registry bean the clients keep their default no-op registry.
   */
  SyncClientPipeline pipelineOf(ClientHttpRequestFactory requestFactory,
      List<ClientHttpRequestInterceptor> interceptors, ClientProperties properties) {
    return new SyncClientPipeline(requestFactory, interceptors, observationRegistry, properties);
  }

  @Override
  public RestClient createRestClient(String clientId, ClientProperties properties) {
    return pipeline(clientId, properties, "RestClient").restClient();
  }

  /**
   * {@inheritDoc}
   *
   * @deprecated since 2.6.0: use {@link #createRestClient(String, ClientProperties)}.
   */
  @Override
  @Deprecated(since = "2.6.0")
  public RestTemplate create(String clientId, ClientProperties properties) {
    return pipeline(clientId, properties, "RestTemplate").restTemplate();
  }

  /**
   * Puts the application's {@link OutboundInterceptorContribution}s at the HEAD of the chain, in
   * their order, ahead of every interceptor of the library's own. Must be invoked before the
   * other interceptor-adding helpers; the token calls run through the same chain. Looked up on
   * every call, so clients built at runtime see the same contributions. Without a contribution
   * bean nothing is added.
   */
  protected void addContributedInterceptors(List<ClientHttpRequestInterceptor> interceptors) {
    contributionsProvider.orderedStream()
        .map(OutboundInterceptorContribution::interceptor)
        .forEach(interceptors::add);
  }

  /**
   * As {@link #addContributedInterceptors(List)}, on the template's own list.
   *
   * @deprecated since 2.6.0: use {@link #addContributedInterceptors(List)}.
   */
  @Deprecated(since = "2.6.0")
  protected void addContributedInterceptors(RestTemplate restTemplate) {
    addContributedInterceptors(restTemplate.getInterceptors());
  }

  /**
   * Hands the application's {@link ObservationRegistry} to the {@code RestTemplate}; without a
   * registry bean the template keeps its default no-op registry.
   *
   * @deprecated since 2.6.0: the {@link SyncClientPipeline} applies the registry to both shapes.
   */
  @Deprecated(since = "2.6.0")
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
   * {@link #addContributedInterceptors(List)} and before the other interceptor-adding helpers,
   * so that the bulkhead and circuit breaker wrap the whole downstream chain.
   */
  protected void addResilienceInterceptor(List<ClientHttpRequestInterceptor> interceptors,
      String clientId, ClientProperties properties) {
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
    interceptors.add(new ResilienceClientHttpRequestInterceptor(clientId,
        circuitBreaker, bulkhead,
        Set.copyOf(resilience.getCircuitBreaker().getRecordStatusCodes())));
    log.debug("Resilience decoration enabled for client '{}' (bulkhead: {})", clientId,
        bulkhead != null);
  }

  /**
   * As {@link #addResilienceInterceptor(List, String, ClientProperties)}, on the template's own
   * list.
   *
   * @deprecated since 2.6.0: use {@link #addResilienceInterceptor(List, String,
   *     ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  protected void addResilienceInterceptor(RestTemplate restTemplate, String clientId,
      ClientProperties properties) {
    addResilienceInterceptor(restTemplate.getInterceptors(), clientId, properties);
  }

  protected void addFixedHeadersInterceptor(List<ClientHttpRequestInterceptor> interceptors,
      ClientProperties properties) {
    if (!CollectionUtils.isEmpty(properties.getFixedHeaders())) {
      interceptors.add(new FixedHeadersClientHttpRequestInterceptor(properties.getFixedHeaders()));
    }
  }

  /**
   * As {@link #addFixedHeadersInterceptor(List, ClientProperties)}, on the template's own list.
   *
   * @deprecated since 2.6.0: use {@link #addFixedHeadersInterceptor(List, ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  protected void addFixedHeadersInterceptor(RestTemplate restTemplate,
      ClientProperties properties) {
    addFixedHeadersInterceptor(restTemplate.getInterceptors(), properties);
  }

  /**
   * As {@link #addLogbookInterceptor(List, ClientProperties)}, on the template's own list.
   *
   * @deprecated since 2.6.0: use {@link #addLogbookInterceptor(List, ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  protected void addLogbookInterceptor(RestTemplate restTemplate, ClientProperties properties) {
    addLogbookInterceptor(restTemplate.getInterceptors(), properties);
  }

  protected void addLogbookInterceptor(List<ClientHttpRequestInterceptor> interceptors,
      ClientProperties properties) {
    if (properties.isLoggingEnabled()) {
      if (logbookSupport != null) {
        logbookSupport.addInterceptor(interceptors);
      } else {
        log.warn("Client has logging-enabled: true, but no Logbook bean found. "
            + "Add org.zalando:logbook-spring to your classpath to enable HTTP logging.");
      }
    }
  }
}
