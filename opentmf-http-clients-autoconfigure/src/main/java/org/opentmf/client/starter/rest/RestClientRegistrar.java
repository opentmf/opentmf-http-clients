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
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.util.BearerAuthRequirements;
import org.opentmf.client.common.util.TokenCallProperties;
import org.opentmf.client.rest.service.api.RestClientFactory;
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
  private final Map<ClientType, AbstractRestTemplateFactory> backends;
  private final ObjectProvider<TokenFetchMeters> tokenFetchMetersProvider;
  private final @Nullable RestLogbookSupport logbookSupport;

  /**
   * @param factoryBeans the library's JDK and Apache backends, by bean name
   * @since 2.6.0
   */
  @Autowired
  public RestClientRegistrar(ConfigurableApplicationContext ctx,
      ObjectProvider<TokenFetchMeters> tokenFetchMetersProvider,
      ObjectProvider<RestLogbookSupport> logbookSupportProvider,
      Map<String, RestClientFactory> factoryBeans) {
    this.factory = ctx.getBeanFactory();
    this.backends = factoryBeans.values().stream()
        .collect(Collectors.toMap(this::detectType, RestClientRegistrar::backend));
    this.tokenFetchMetersProvider = tokenFetchMetersProvider;
    this.logbookSupport = logbookSupportProvider.getIfAvailable();
  }

  /**
   * As {@link #RestClientRegistrar(ConfigurableApplicationContext, ObjectProvider,
   * ObjectProvider, Map)}, with the backends by their {@code RestTemplateFactory} type.
   *
   * @deprecated since 2.6.0: use the constructor taking the {@link RestClientFactory} beans.
   */
  @Deprecated(since = "2.6.0")
  public RestClientRegistrar(ConfigurableApplicationContext ctx,
      Map<String, RestTemplateFactory> factoryBeans,
      ObjectProvider<TokenFetchMeters> tokenFetchMetersProvider,
      ObjectProvider<RestLogbookSupport> logbookSupportProvider) {
    this(ctx, tokenFetchMetersProvider, logbookSupportProvider,
        factoryBeans.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
            entry -> restClientFactory(entry.getValue()))));
  }

  /**
   * Registers the client's {@code <id>RestClient}, {@code <id>RestTemplate} and
   * {@code <id>TokenService} beans, all three over one {@link SyncClientPipeline}, so over one
   * connection pool.
   */
  @SuppressWarnings("deprecation") // the <id>RestTemplate bean stays registered by default
  public void registerBeans(String clientId, ClientType clientType, ClientProperties properties) {
    BearerAuthRequirements.requireTokenUrl(clientId, properties);
    var pipeline = createPipeline(clientId, clientType, properties);
    registerIfAbsent(clientId + REST_TEMPLATE, pipeline.restTemplate());
    registerIfAbsent(clientId + REST_CLIENT, pipeline.restClient());
    registerIfAbsent(clientId + TOKEN_SERVICE, createTokenService(clientId, pipeline));
  }

  /**
   * Builds the {@link SyncClientPipeline} of the given client type without registering any bean
   * — the entry point for lifecycle-managing callers such as the dynamic-client registry. Its
   * {@code restClient()} is the client; its {@code requestFactory()} owns the connection pool.
   *
   * @throws IllegalStateException when no factory for the client type is on the classpath
   * @since 2.6.0
   */
  public SyncClientPipeline createPipeline(String clientId, ClientType clientType,
      ClientProperties properties) {
    return backend(clientId, clientType).pipeline(clientId, properties);
  }

  /**
   * Builds a fully configured {@code RestClient} for the given client type without registering
   * any bean. As {@code createPipeline(clientId, clientType, properties).restClient()}.
   *
   * @throws IllegalStateException when no factory for the client type is on the classpath
   * @since 2.6.0
   */
  public RestClient createRestClient(String clientId, ClientType clientType,
      ClientProperties properties) {
    return createPipeline(clientId, clientType, properties).restClient();
  }

  /**
   * Builds a fully configured {@code RestTemplate} for the given client type without registering
   * any bean.
   *
   * @throws IllegalStateException when no factory for the client type is on the classpath
   * @deprecated since 2.6.0: use {@link #createRestClient(String, ClientType, ClientProperties)}
   *     or {@link #createPipeline(String, ClientType, ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  public RestTemplate createRestTemplate(String clientId, ClientType clientType,
      ClientProperties properties) {
    return backend(clientId, clientType).create(clientId, properties);
  }

  private AbstractRestTemplateFactory backend(String clientId, ClientType clientType) {
    var backend = backends.get(clientType);
    if (backend == null) {
      var msg = "Client '" + clientId + "' requires client-type: " + clientType
          + ", but no matching library is on the classpath. "
          + "Available implementations: " + backends.keySet() + ". "
          + (clientType == ClientType.APACHE
              ? "Add org.apache.httpcomponents.client5:httpclient5 to your classpath."
              : "Check your dependencies.");
      log.error(msg);
      throw new IllegalStateException(msg);
    }
    return backend;
  }

  /**
   * Builds the {@code RestClient} view of the given {@code RestTemplate}, inheriting its request
   * factory and interceptors and adding the library's error-wrapping status handler. Its error
   * messages carry the peer's text whole and cut a non-JSON body at 2000 characters, as in
   * earlier releases; it ignores every client setting, which
   * {@link #createRestClient(RestTemplate, ClientProperties)} follows.
   *
   * @deprecated since 2.6.0: use {@link #createRestClient(String, ClientType, ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  public RestClient createRestClient(RestTemplate restTemplate) {
    return createRestClient(restTemplate, OpenTmfRestClientStatusHandler.errorHandler());
  }

  /**
   * As {@link #createRestClient(RestTemplate)}, its error messages following the client's
   * {@code peer-text-in-message} and {@code peer-text-cap-length}.
   *
   * @throws IllegalArgumentException when the cap length is outside its bounds
   * @deprecated since 2.6.0: use {@link #createRestClient(String, ClientType, ClientProperties)}.
   */
  @Deprecated(since = "2.6.0")
  public RestClient createRestClient(RestTemplate restTemplate, ClientProperties properties) {
    return createRestClient(restTemplate, OpenTmfRestClientStatusHandler.errorHandler(properties));
  }

  private static RestClient createRestClient(RestTemplate restTemplate,
      RestClient.ResponseSpec.ErrorHandler errorHandler) {
    return RestClient.builder(restTemplate)
        .defaultStatusHandler(HttpStatusCode::isError, errorHandler)
        .build();
  }

  /**
   * Builds the token service matching the client's auth configuration. Token calls run through
   * the pipeline's {@code RestClient}, so they share its decoration (incl. resilience) — all but
   * its {@code fixed-headers}, which never reach the token endpoint. Bearer mints are counted on
   * {@code opentmf.client.token.fetch{client=<clientId>}} when a {@code MeterRegistry} bean
   * exists.
   *
   * <p>The token calls run on the client's own timeouts and retries, except where
   * {@code bearer-auth} sets the token request's own {@code request-timeout},
   * {@code response-timeout} or {@code num-retries}; the token's own timeouts are applied over the
   * pipeline's HTTP client, so the connection pool stays shared.</p>
   *
   * @since 2.6.0
   */
  public SyncTokenService createTokenService(String clientId, SyncClientPipeline pipeline) {
    return createTokenService(clientId, pipeline.requestFactory(), pipeline.restClient(),
        pipeline.properties());
  }

  /**
   * As {@link #createTokenService(String, SyncClientPipeline)}, over the given
   * {@code RestTemplate}'s HTTP client and {@code RestClient}; a {@code null} client id opts out
   * of the token-fetch counter.
   *
   * @deprecated since 2.6.0: use {@link #createTokenService(String, SyncClientPipeline)}.
   */
  @Deprecated(since = "2.6.0")
  public SyncTokenService createTokenService(@Nullable String clientId,
      RestTemplate restTemplate, RestClient restClient, ClientProperties properties) {
    return createTokenService(clientId, rawRequestFactory(restTemplate), restClient, properties);
  }

  private SyncTokenService createTokenService(@Nullable String clientId,
      @Nullable ClientHttpRequestFactory clientFactory, RestClient restClient,
      ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpSyncTokenService();
      case BASIC -> new SyncBasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildSyncBearerTokenService(clientId, clientFactory, restClient, properties);
    };
  }

  /**
   * As {@link #createTokenService(String, SyncClientPipeline)}, without the client's HTTP
   * client: the token request's own {@code request-timeout} / {@code response-timeout} cannot be
   * applied then, and setting them fails. A {@code null} client id opts out of the token-fetch
   * counter.
   */
  public SyncTokenService createTokenService(@Nullable String clientId, RestClient restClient,
      ClientProperties properties) {
    return createTokenService(clientId, (ClientHttpRequestFactory) null, restClient, properties);
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
      @Nullable ClientHttpRequestFactory clientFactory, RestClient restClient,
      ClientProperties properties) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new SyncBearerTokenServiceMockImpl();
    }
    BearerAuthRequirements.requireTokenUrl(clientId, properties);
    var tokenFactory = tokenRequestFactory(clientId, clientFactory, properties);
    var syncTokenClient = new SyncTokenClientImpl(
        tokenRestClient(restClient, tokenFactory, bearerConfig), properties, bearerConfig,
        tokenFetchListener(clientId));
    var cache = TokenCacheUtil.buildTokenCache();
    return new SyncBearerTokenServiceImpl(bearerConfig, cache, syncTokenClient,
        properties.getMaxRetryAfter());
  }

  /**
   * The token calls' own request factory when {@code bearer-auth} sets the token request's own
   * timeouts, else {@code null}: the token calls then run on the client's request factory.
   */
  private static @Nullable ClientHttpRequestFactory tokenRequestFactory(@Nullable String clientId,
      @Nullable ClientHttpRequestFactory clientFactory, ClientProperties properties) {
    var tokenProperties = TokenCallProperties.of(properties);
    if (tokenProperties == properties) {
      return null;
    }
    if (clientFactory == null) {
      if (tokenProperties.getRequestTimeout().equals(properties.getRequestTimeout())
          && tokenProperties.getResponseTimeout().equals(properties.getResponseTimeout())) {
        return null;
      }
      throw new IllegalStateException("Client '" + clientId + "' sets bearer-auth."
          + "request-timeout / bearer-auth.response-timeout, which need the client's "
          + "HTTP client: use createTokenService(clientId, pipeline)");
    }
    return TokenRequestFactories.forTokenCalls(clientFactory, properties, tokenProperties);
  }

  /**
   * The template's own request factory. With interceptors present,
   * {@code RestTemplate.getRequestFactory()} returns an intercepting facade over it; clearing the
   * interceptor list for the lookup returns the factory itself.
   */
  @SuppressWarnings("deprecation") // serves the deprecated RestTemplate path only
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
   * {@code tokenFactory} is given), the contributions, resilience and Logbook — the latter
   * withholding the token endpoint's response body unless {@code bearer-auth.log-response-body}
   * is set.
   */
  private RestClient tokenRestClient(RestClient restClient,
      @Nullable ClientHttpRequestFactory tokenFactory, BearerAuthConfig bearerConfig) {
    var builder = restClient.mutate()
        .requestInterceptors(interceptors -> {
          interceptors.removeIf(FixedHeadersClientHttpRequestInterceptor.class::isInstance);
          if (logbookSupport != null && !bearerConfig.isLogResponseBody()) {
            logbookSupport.useTokenCallInterceptor(interceptors);
          }
        });
    if (tokenFactory != null) {
      builder.requestFactory(tokenFactory);
    }
    return builder.build();
  }

  private TokenFetchListener tokenFetchListener(@Nullable String clientId) {
    var meters = clientId == null ? null : tokenFetchMetersProvider.getIfAvailable();
    return meters == null ? TokenFetchListener.noop() : meters.listenerFor(clientId);
  }

  private ClientType detectType(RestClientFactory factory) {
    if (factory instanceof ApacheRestTemplateFactory) return ClientType.APACHE;
    if (factory instanceof JdkRestTemplateFactory) return ClientType.JDK;
    throw new IllegalStateException("Unknown RestClientFactory type: " + factory.getClass()
        + ". Available implementations: Apache HttpClient 5, JDK HttpClient.");
  }

  private static AbstractRestTemplateFactory backend(RestClientFactory factory) {
    return (AbstractRestTemplateFactory) factory;
  }

  /** A library backend given by its deprecated type, as the one it also is. */
  @SuppressWarnings("deprecation")
  private static RestClientFactory restClientFactory(RestTemplateFactory factory) {
    if (factory instanceof RestClientFactory restClientFactory) {
      return restClientFactory;
    }
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
