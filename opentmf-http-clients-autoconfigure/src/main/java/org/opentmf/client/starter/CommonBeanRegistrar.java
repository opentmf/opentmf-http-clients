package org.opentmf.client.starter;

import static org.opentmf.client.common.util.TokenUtil.CLIENT_PROPERTIES;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.starter.reactive.ReactiveClientRegistrar;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

@Slf4j
public class CommonBeanRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final OpentmfHttpClientsConfig config;
  private final Optional<ReactiveClientRegistrar> reactiveRegistrar;
  private final Optional<RestClientRegistrar> restRegistrar;

  public CommonBeanRegistrar(ConfigurableApplicationContext ctx, OpentmfHttpClientsConfig config) {
    this.factory = ctx.getBeanFactory();
    this.config = config;
    this.reactiveRegistrar = safeGetBean(ctx, ReactiveClientRegistrar.class);
    this.restRegistrar = safeGetBean(ctx, RestClientRegistrar.class);
  }

  public void registerBeans(String clientId, ClientProperties properties) {
    registerIfAbsent(clientId + CLIENT_PROPERTIES, properties);

    ClientType effectiveType = config.resolveClientType(properties);
    if (effectiveType.isReactive()) {
      reactiveRegistrar
          .orElseThrow(() -> new IllegalStateException(
              "Client '" + clientId + "' requires client-type: netty, "
              + "but spring-webflux is not on the classpath. "
              + "Add spring-boot-starter-webflux or change to client-type: jdk."))
          .registerBeans(clientId, properties);
    } else {
      restRegistrar
          .orElseThrow(() -> new IllegalStateException(
              "Client '" + clientId + "' requires client-type: " + effectiveType
              + ", but RestTemplate is not available."))
          .registerBeans(clientId, effectiveType, properties);
    }
  }

  private void registerIfAbsent(String beanName, Object bean) {
    if (!factory.containsBean(beanName)) {
      factory.registerSingleton(beanName, bean);
      log.debug("Exposed: {}", beanName);
    }
  }

  private static <T> Optional<T> safeGetBean(ConfigurableApplicationContext ctx, Class<T> type) {
    try {
      return Optional.of(ctx.getBean(type));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
