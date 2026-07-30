package org.opentmf.client.starter.reactive;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;
import static org.opentmf.client.common.util.TokenUtil.WEB_CLIENT;

import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
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
    registerIfAbsent(clientId + TOKEN_SERVICE, buildTokenService(clientId, properties));
  }

  /**
   * Builds a WebClient. The {@code resilienceName} deliberately differs from
   * {@code connectionName} for token clients: the token WebClient gets its own connection pool
   * ({@code <id>Token}) but shares the OWNING client's resilience instances, so a broken token
   * endpoint opens the same circuit.
   */
  private WebClient buildWebClient(String connectionName, String resilienceName,
      ClientProperties properties) {
    try {
      Consumer<Connection> logbookHandler =
          (properties.isLoggingEnabled() && logbookSupport != null)
              ? logbookSupport::addHandler
              : null;
      var httpClient = WebClientConfigUtil.httpClient(logbookHandler, connectionName, properties);
      var webClient = WebClientConfigUtil.createWebClient(webClientBuilder, httpClient, properties);
      return ReactiveResilience.decorate(webClient, resilienceName, properties,
          resilienceRegistriesProvider.getIfAvailable());
    } catch (Exception e) {
      throw new IllegalArgumentException("Can't create WebClient for " + connectionName, e);
    }
  }

  private TokenService buildTokenService(String clientId, ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpTokenService();
      case BASIC -> new BasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildBearerTokenService(clientId, properties);
    };
  }

  private TokenService buildBearerTokenService(String clientId, ClientProperties properties) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new BearerTokenServiceMockImpl();
    }
    var tokenWebClient = buildWebClient(clientId + "Token", clientId, properties);
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
