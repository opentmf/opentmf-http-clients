package org.opentmf.client.starter.reactive;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;
import static org.opentmf.client.common.util.TokenUtil.WEB_CLIENT;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.bearer.reactive.BearerTokenClientImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceMockImpl;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.reactive.resilience.ReactiveResilience;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.reactive.service.impl.BasicTokenServiceImpl;
import org.opentmf.client.reactive.service.impl.NoOpTokenService;
import org.opentmf.client.reactive.util.WebClientConfigUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.Connection;
import reactor.netty.resources.ConnectionProvider;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(WebClient.class)
@Slf4j
public class ReactiveClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final WebClient.Builder webClientBuilder;
  private final ReactiveLogbookSupport logbookSupport;
  private final ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider;

  @Autowired
  public ReactiveClientRegistrar(ConfigurableApplicationContext ctx,
      WebClient.Builder webClientBuilder,
      ObjectProvider<ReactiveLogbookSupport> logbookSupportProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider) {
    this.factory = ctx.getBeanFactory();
    this.webClientBuilder = webClientBuilder;
    this.logbookSupport = logbookSupportProvider.getIfAvailable();
    this.resilienceRegistriesProvider = resilienceRegistriesProvider;
  }

  public void registerBeans(String clientId, ClientProperties properties) {
    if (properties.isLoggingEnabled() && logbookSupport == null) {
      log.warn("Client '{}' has logging-enabled: true, but no Logbook bean found. "
          + "Add org.zalando:logbook-netty to your classpath to enable HTTP logging.", clientId);
    }
    registerIfAbsent(clientId + WEB_CLIENT, buildWebClient(clientId, clientId, properties));
    registerIfAbsent(clientId + TOKEN_SERVICE, createTokenService(clientId, properties, null));
  }

  private WebClient buildWebClient(String connectionName, String resilienceName,
      ClientProperties properties) {
    return createWebClient(connectionName, resilienceName, properties,
        WebClientConfigUtil.buildConnectionProvider(connectionName, properties));
  }

  /**
   * Builds a fully configured {@code WebClient} without registering any bean — the entry point
   * for lifecycle-managing callers such as the dynamic-client registry, which supply their own
   * {@link ConnectionProvider} so they can dispose it on eviction.
   *
   * <p>The {@code resilienceName} deliberately differs from {@code connectionName} for token
   * clients: the token WebClient gets its own connection pool ({@code <id>Token}) but shares the
   * OWNING client's resilience instances, so a broken token endpoint opens the same circuit.</p>
   */
  public WebClient createWebClient(String connectionName, String resilienceName,
      ClientProperties properties, ConnectionProvider connectionProvider) {
    try {
      Consumer<Connection> logbookHandler =
          (properties.isLoggingEnabled() && logbookSupport != null)
              ? logbookSupport::addHandler
              : null;
      var httpClient = WebClientConfigUtil.httpClient(
          logbookHandler, properties, connectionProvider);
      var webClient = WebClientConfigUtil.createWebClient(webClientBuilder, httpClient, properties);
      return ReactiveResilience.decorate(webClient, resilienceName, properties,
          resilienceRegistriesProvider.getIfAvailable());
    } catch (Exception e) {
      throw new IllegalArgumentException("Can't create WebClient for " + connectionName, e);
    }
  }

  /**
   * Builds the token service matching the client's auth configuration. For bearer auth,
   * {@code tokenWebClient} may be supplied by lifecycle-managing callers (so they own its
   * connection provider); when {@code null}, a token WebClient named {@code <clientId>Token} is
   * built internally, sharing the owning client's resilience instances.
   */
  public TokenService createTokenService(String clientId, ClientProperties properties,
      @Nullable WebClient tokenWebClient) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpTokenService();
      case BASIC -> new BasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildBearerTokenService(clientId, properties, tokenWebClient);
    };
  }

  private TokenService buildBearerTokenService(String clientId, ClientProperties properties,
      @Nullable WebClient suppliedTokenWebClient) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new BearerTokenServiceMockImpl();
    }
    var tokenWebClient = suppliedTokenWebClient != null
        ? suppliedTokenWebClient
        : buildWebClient(clientId + "Token", clientId, properties);
    var tokenClient = new BearerTokenClientImpl(properties, bearerConfig, tokenWebClient);
    var cache = TokenCacheUtil.buildTokenCache();
    return new BearerTokenServiceImpl(bearerConfig, cache, tokenClient);
  }

  private void registerIfAbsent(String beanName, Object bean) {
    if (!factory.containsBean(beanName)) {
      factory.registerSingleton(beanName, bean);
      log.debug("Exposed: {}", beanName);
    }
  }
}
