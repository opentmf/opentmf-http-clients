package org.opentmf.client.starter.rest;

import static org.opentmf.client.common.util.TokenUtil.REST_TEMPLATE;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_SERVICE;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceMockImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.rest.service.impl.NoOpSyncTokenService;
import org.opentmf.client.rest.service.impl.SyncBasicTokenServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
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
    RestTemplateFactory rtFactory = restTemplateFactories.get(clientType);
    if (rtFactory == null) {
      throw new IllegalStateException(
          "Client '" + clientId + "' requires client-type: " + clientType
          + ", but no matching library is on the classpath. Available: "
          + restTemplateFactories.keySet());
    }
    registerIfAbsent(clientId + REST_TEMPLATE, rtFactory.create(clientId, properties));
    registerIfAbsent(clientId + TOKEN_SERVICE,
        buildSyncTokenService(clientId, clientType, properties));
  }

  private SyncTokenService buildSyncTokenService(String clientId, ClientType clientType,
      ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE -> new NoOpSyncTokenService();
      case BASIC -> new SyncBasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER -> buildSyncBearerTokenService(clientId, clientType, properties);
    };
  }

  private SyncTokenService buildSyncBearerTokenService(String clientId, ClientType clientType,
      ClientProperties properties) {
    var bearerConfig = properties.getBearerAuth();
    if (bearerConfig.isUseMock()) {
      return new SyncBearerTokenServiceMockImpl();
    }
    RestTemplateFactory rtFactory = restTemplateFactories.get(clientType);
    var tokenRestTemplate = rtFactory.create(clientId + "Token", properties);
    var syncTokenClient = new SyncTokenClientImpl(tokenRestTemplate, bearerConfig);
    var cache = buildTokenCache();
    return new SyncBearerTokenServiceImpl(bearerConfig, cache, syncTokenClient);
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
