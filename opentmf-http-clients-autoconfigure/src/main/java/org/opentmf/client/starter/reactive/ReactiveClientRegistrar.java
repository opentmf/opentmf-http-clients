package org.opentmf.client.starter.reactive;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;
import static org.opentmf.client.common.util.TokenUtil.WEB_CLIENT;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.reactive.BearerTokenClientImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceMockImpl;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.reactive.service.impl.BasicTokenServiceImpl;
import org.opentmf.client.reactive.service.impl.NoOpTokenService;
import org.opentmf.client.reactive.util.WebClientConfigUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(WebClient.class)
@Slf4j
public class ReactiveClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final WebClient.Builder webClientBuilder;
  private final Logbook logbook;

  @Autowired
  public ReactiveClientRegistrar(ConfigurableApplicationContext ctx,
      WebClient.Builder webClientBuilder, Logbook logbook) {
    this.factory = ctx.getBeanFactory();
    this.webClientBuilder = webClientBuilder;
    this.logbook = logbook;
  }

  public void registerBeans(String clientId, ClientProperties properties) {
    registerIfAbsent(clientId + WEB_CLIENT, buildWebClient(clientId, properties));
    registerIfAbsent(clientId + TOKEN_SERVICE, buildTokenService(clientId, properties));
  }

  private WebClient buildWebClient(String clientId, ClientProperties properties) {
    try {
      var httpClient = WebClientConfigUtil.httpClient(logbook, clientId, properties);
      return WebClientConfigUtil.createWebClient(webClientBuilder, httpClient, properties);
    } catch (Exception e) {
      throw new IllegalArgumentException("Can't create WebClient for " + clientId, e);
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
    var tokenWebClient = buildWebClient(clientId + "Token", properties);
    var tokenClient = new BearerTokenClientImpl(properties, bearerConfig, tokenWebClient);
    var cache = buildTokenCache();
    return new BearerTokenServiceImpl(bearerConfig, cache, tokenClient);
  }

  private Cache<String, TokenEntry> buildTokenCache() {
    return Caffeine.newBuilder()
        .expireAfter(new Expiry<String, TokenEntry>() {
          @Override
          public long expireAfterCreate(String key, TokenEntry entry, long currentTime) {
            return entry.getCacheDuration().toNanos();
          }

          @Override
          public long expireAfterUpdate(String key, TokenEntry entry,
              long currentTime, long currentDuration) {
            return entry.getCacheDuration().toNanos();
          }

          @Override
          public long expireAfterRead(String key, TokenEntry entry,
              long currentTime, long currentDuration) {
            return currentDuration;
          }
        })
        .build();
  }

  private void registerIfAbsent(String beanName, Object bean) {
    if (!factory.containsBean(beanName)) {
      factory.registerSingleton(beanName, bean);
      log.debug("Exposed: {}", beanName);
    }
  }
}
