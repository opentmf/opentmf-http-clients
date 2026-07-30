package org.opentmf.client.starter;

import java.time.Duration;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.starter.reactive.ReactiveClientRegistrar;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@EnableConfigurationProperties(OpentmfHttpClientsConfig.class)
@Import({
    StringToClientTypeConverter.class,
    ResilienceAutoConfiguration.class,
    ApachePoolMetersConfiguration.class,
    org.opentmf.client.starter.reactive.ReactiveClientRegistrar.class,
    org.opentmf.client.starter.reactive.ReactiveLogbookAutoConfiguration.class,
    org.opentmf.client.starter.rest.RestClientRegistrar.class,
    org.opentmf.client.starter.rest.ApacheRestTemplateFactory.class,
    org.opentmf.client.starter.rest.JdkRestTemplateFactory.class,
    org.opentmf.client.starter.rest.RestLogbookAutoConfiguration.class
})
public class OpentmfHttpClientsAutoConfiguration {

  private static final Duration DYNAMIC_CLIENT_CLOSE_GRACE = Duration.ofSeconds(30);

  public OpentmfHttpClientsAutoConfiguration(
      ConfigurableApplicationContext ctx,
      OpentmfHttpClientsConfig config) {

    var registrar = new CommonBeanRegistrar(ctx, config);
    config.getHttpClients().forEach(registrar::registerBeans);
  }

  @Bean
  public String opentmfHttpClientsStarter() {
    return "opentmfHttpClientsStarter";
  }

  /**
   * Lifecycle registry for clients built programmatically at runtime (dynamic sources). The
   * static {@code opentmf.http-clients.*} beans are unaffected by it.
   */
  @Bean
  public HttpClientRegistry opentmfHttpClientRegistry(
      ObjectProvider<RestClientRegistrar> restRegistrarProvider,
      ObjectProvider<ReactiveClientRegistrar> reactiveRegistrarProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider,
      ObjectProvider<ApachePoolMeters> poolMetersProvider) {
    return new HttpClientRegistry(
        restRegistrarProvider.getIfAvailable(),
        reactiveRegistrarProvider.getIfAvailable(),
        resilienceRegistriesProvider,
        poolMetersProvider,
        DYNAMIC_CLIENT_CLOSE_GRACE);
  }
}
