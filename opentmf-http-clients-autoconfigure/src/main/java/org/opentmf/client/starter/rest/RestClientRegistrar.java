package org.opentmf.client.starter.rest;

import static org.opentmf.client.common.util.TokenUtil.REST_CLIENT;
import static org.opentmf.client.common.util.TokenUtil.REST_TEMPLATE;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;

import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceMockImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.rest.service.impl.NoOpSyncTokenService;
import org.opentmf.client.rest.service.impl.SyncBasicTokenServiceImpl;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RestTemplate.class)
@Slf4j
public class RestClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final Map<ClientType, RestTemplateFactory> restTemplateFactories;

  @Autowired
  public RestClientRegistrar(ConfigurableApplicationContext ctx,
      Map<String, RestTemplateFactory> factoryBeans) {
    this.factory = ctx.getBeanFactory();
    this.restTemplateFactories = factoryBeans.values().stream()
        .collect(Collectors.toMap(this::detectType, f -> f));
  }

  public void registerBeans(String clientId, ClientType clientType, ClientProperties properties) {
    var restTemplate = createRestTemplate(clientId, clientType, properties);
    var restClient = createRestClient(restTemplate);
    registerIfAbsent(clientId + REST_TEMPLATE, restTemplate);
    registerIfAbsent(clientId + REST_CLIENT, restClient);
    registerIfAbsent(clientId + TOKEN_SERVICE,
        createTokenService(restClient, properties));
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
   * the given {@code RestClient}, so they share its decoration (incl. resilience).
   */
  public SyncTokenService createTokenService(RestClient restClient,
      ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpSyncTokenService();
      case BASIC -> new SyncBasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildSyncBearerTokenService(restClient, properties);
    };
  }

  private SyncTokenService buildSyncBearerTokenService(RestClient restClient,
      ClientProperties properties) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new SyncBearerTokenServiceMockImpl();
    }
    var syncTokenClient = new SyncTokenClientImpl(restClient, bearerConfig);
    var cache = TokenCacheUtil.buildTokenCache();
    return new SyncBearerTokenServiceImpl(bearerConfig, cache, syncTokenClient);
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
