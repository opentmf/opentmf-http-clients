package org.opentmf.client.starter;

import static org.opentmf.client.common.util.TokenUtil.CLIENT_PROPERTIES;

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
  private final ReactiveClientRegistrar reactiveRegistrar;
  private final RestClientRegistrar restRegistrar;

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
      if (reactiveRegistrar == null) {
        var msg = "Client '" + clientId + "' is configured with client-type: netty, "
            + "but spring-webflux and reactor-netty are not on the classpath. "
            + "Add opentmf-http-clients-starter-reactive or spring-boot-starter-webflux, "
            + "or change to client-type: jdk.";
        log.error(msg);
        throw new IllegalStateException(msg);
      }
      reactiveRegistrar.registerBeans(clientId, properties);
    } else {
      if (restRegistrar == null) {
        var msg = "Client '" + clientId + "' requires client-type: " + effectiveType
            + ", but RestTemplate is not available. "
            + "Add opentmf-http-clients-starter-rest to your classpath.";
        log.error(msg);
        throw new IllegalStateException(msg);
      }
      restRegistrar.registerBeans(clientId, effectiveType, properties);
    }
  }

  private void registerIfAbsent(String beanName, Object bean) {
    if (!factory.containsBean(beanName)) {
      factory.registerSingleton(beanName, bean);
      log.debug("Exposed: {}", beanName);
    }
  }

  private static <T> T safeGetBean(ConfigurableApplicationContext ctx, Class<T> type) {
    try {
      return ctx.getBean(type);
    } catch (Exception e) {
      return null;
    }
  }
}
