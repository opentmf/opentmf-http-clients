package org.opentmf.client.starter.rest;

import static org.opentmf.client.common.util.TokenUtil.REST_CLIENT;
import static org.opentmf.client.common.util.TokenUtil.REST_TEMPLATE;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.bearer.observe.TokenFetchListener;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceMockImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.util.TokenCallProperties;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.rest.service.impl.NoOpSyncTokenService;
import org.opentmf.client.rest.service.impl.SyncBasicTokenServiceImpl;
import org.opentmf.client.rest.util.FixedHeadersClientHttpRequestInterceptor;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.opentmf.client.starter.TokenFetchMeters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
// The mirror of the reactive registrar's rule: conditioned on the REST MODULE's presence
// (SyncTokenService), not only on RestTemplate's — a reactive-only adopter carries spring-web
// without opentmf-http-clients-rest.
@ConditionalOnClass(name = {
    "org.springframework.web.client.RestTemplate",
    "org.opentmf.client.rest.service.api.SyncTokenService"})
@Slf4j
public class RestClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final Map<ClientType, RestTemplateFactory> restTemplateFactories;
  private final ObjectProvider<TokenFetchMeters> tokenFetchMetersProvider;

  @Autowired
  public RestClientRegistrar(ConfigurableApplicationContext ctx,
      Map<String, RestTemplateFactory> factoryBeans,
      ObjectProvider<TokenFetchMeters> tokenFetchMetersProvider) {
    this.factory = ctx.getBeanFactory();
    this.restTemplateFactories = factoryBeans.values().stream()
        .collect(Collectors.toMap(this::detectType, f -> f));
    this.tokenFetchMetersProvider = tokenFetchMetersProvider;
  }

  public void registerBeans(String clientId, ClientType clientType, ClientProperties properties) {
    var restTemplate = createRestTemplate(clientId, clientType, properties);
    var restClient = createRestClient(restTemplate);
    registerIfAbsent(clientId + REST_TEMPLATE, restTemplate);
    registerIfAbsent(clientId + REST_CLIENT, restClient);
    registerIfAbsent(clientId + TOKEN_SERVICE,
        createTokenService(clientId, restTemplate, restClient, properties));
  }

  /**
   * Builds a fully configured {@code RestTemplate} for the given client type without registering
   * any bean — the entry point for lifecycle-managing callers such as the dynamic-client
   * registry.
   *
   * @throws IllegalStateException when no factory for the client type is on the classpath
   */
  public RestTemplate createRestTemplate(String clientId, ClientType clientType,
      ClientProperties properties) {
    RestTemplateFactory rtFactory = restTemplateFactories.get(clientType);
    if (rtFactory == null) {
      var msg = "Client '" + clientId + "' requires client-type: " + clientType
          + ", but no matching library is on the classpath. "
          + "Available implementations: " + restTemplateFactories.keySet() + ". "
          + (clientType == ClientType.APACHE
              ? "Add org.apache.httpcomponents.client5:httpclient5 to your classpath."
              : "Check your dependencies.");
      log.error(msg);
      throw new IllegalStateException(msg);
    }
    return rtFactory.create(clientId, properties);
  }

  /**
   * Builds the {@code RestClient} view of the given {@code RestTemplate}, inheriting its request
   * factory and interceptors and adding the library's error-wrapping status handler.
   */
  public RestClient createRestClient(RestTemplate restTemplate) {
    return RestClient.builder(restTemplate)
        .defaultStatusHandler(HttpStatusCode::isError,
            OpenTmfRestClientStatusHandler.errorHandler())
        .build();
  }

  /**
   * Builds the token service matching the client's auth configuration. Token calls run through
   * the given {@code RestClient}, so they share its decoration (incl. resilience) — all but its
   * {@code fixed-headers}, which never reach the token endpoint. Bearer mints
   * are counted on {@code opentmf.client.token.fetch{client=<clientId>}} when a
   * {@code MeterRegistry} bean exists; a {@code null} client id opts out of the counter.
   *
   * <p>The token calls run on the client's own timeouts and retries, except where
   * {@code bearer-auth} sets the token request's own {@code request-timeout},
   * {@code response-timeout} or {@code num-retries}; the token's own timeouts are applied over the
   * given {@code RestTemplate}'s HTTP client, so the connection pool stays shared.</p>
   */
  public SyncTokenService createTokenService(@Nullable String clientId,
      RestTemplate restTemplate, RestClient restClient, ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpSyncTokenService();
      case BASIC -> new SyncBasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildSyncBearerTokenService(clientId, restTemplate, restClient, properties);
    };
  }

  /**
   * As {@link #createTokenService(String, RestTemplate, RestClient, ClientProperties)}, without
   * the client's {@code RestTemplate}: the token request's own {@code request-timeout} /
   * {@code response-timeout} cannot be applied then, and setting them fails.
   */
  public SyncTokenService createTokenService(@Nullable String clientId, RestClient restClient,
      ClientProperties properties) {
    return createTokenService(clientId, null, restClient, properties);
  }

  /**
   * As {@link #createTokenService(String, RestClient, ClientProperties)} but without a client
   * id, hence without token-fetch metrics.
   */
  public SyncTokenService createTokenService(RestClient restClient,
      ClientProperties properties) {
    return createTokenService(null, restClient, properties);
  }

  private SyncTokenService buildSyncBearerTokenService(@Nullable String clientId,
      @Nullable RestTemplate restTemplate, RestClient restClient, ClientProperties properties) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new SyncBearerTokenServiceMockImpl();
    }
    var tokenFactory = tokenRequestFactory(clientId, restTemplate, properties);
    var syncTokenClient = new SyncTokenClientImpl(tokenRestClient(restClient, tokenFactory),
        properties, bearerConfig, tokenFetchListener(clientId));
    var cache = TokenCacheUtil.buildTokenCache();
    return new SyncBearerTokenServiceImpl(bearerConfig, cache, syncTokenClient);
  }

  /**
   * The token calls' own request factory when {@code bearer-auth} sets the token request's own
   * timeouts, else {@code null}: the token calls then run on the client's request factory.
   */
  private static @Nullable ClientHttpRequestFactory tokenRequestFactory(@Nullable String clientId,
      @Nullable RestTemplate restTemplate, ClientProperties properties) {
    var tokenProperties = TokenCallProperties.of(properties);
    if (tokenProperties == properties) {
      return null;
    }
    if (restTemplate == null) {
      if (tokenProperties.getRequestTimeout().equals(properties.getRequestTimeout())
          && tokenProperties.getResponseTimeout().equals(properties.getResponseTimeout())) {
        return null;
      }
      throw new IllegalStateException("Client '" + clientId + "' sets bearer-auth."
          + "request-timeout / bearer-auth.response-timeout, which need the client's "
          + "RestTemplate: use createTokenService(clientId, restTemplate, restClient, properties)");
    }
    return TokenRequestFactories.forTokenCalls(rawRequestFactory(restTemplate), properties,
        tokenProperties);
  }

  /**
   * The template's own request factory. With interceptors present,
   * {@code RestTemplate.getRequestFactory()} returns an intercepting facade over it; clearing the
   * interceptor list for the lookup returns the factory itself.
   */
  private static ClientHttpRequestFactory rawRequestFactory(RestTemplate restTemplate) {
    var interceptors = List.copyOf(restTemplate.getInterceptors());
    restTemplate.getInterceptors().clear();
    try {
      return restTemplate.getRequestFactory();
    } finally {
      restTemplate.getInterceptors().addAll(interceptors);
    }
  }

  /**
   * The client's {@code RestClient} without its fixed-headers interceptor: the fixed headers
   * belong to the API calls and never reach the token endpoint. Everything else is shared — the
   * request factory (so the connection pool; with the token request's own timeouts when
   * {@code tokenFactory} is given), the contributions, resilience and Logbook.
   */
  private static RestClient tokenRestClient(RestClient restClient,
      @Nullable ClientHttpRequestFactory tokenFactory) {
    var builder = restClient.mutate()
        .requestInterceptors(interceptors ->
            interceptors.removeIf(FixedHeadersClientHttpRequestInterceptor.class::isInstance));
    if (tokenFactory != null) {
      builder.requestFactory(tokenFactory);
    }
    return builder.build();
  }

  private TokenFetchListener tokenFetchListener(@Nullable String clientId) {
    var meters = clientId == null ? null : tokenFetchMetersProvider.getIfAvailable();
    return meters == null ? TokenFetchListener.noop() : meters.listenerFor(clientId);
  }

  private ClientType detectType(RestTemplateFactory factory) {
    if (factory instanceof ApacheRestTemplateFactory) return ClientType.APACHE;
    if (factory instanceof JdkRestTemplateFactory) return ClientType.JDK;
    throw new IllegalStateException("Unknown RestTemplateFactory type: " + factory.getClass()
        + ". Available implementations: Apache HttpClient 5, JDK HttpClient.");
  }

  private void registerIfAbsent(String beanName, Object bean) {
    if (!factory.containsBean(beanName)) {
      factory.registerSingleton(beanName, bean);
      log.debug("Exposed: {}", beanName);
    }
  }
}
