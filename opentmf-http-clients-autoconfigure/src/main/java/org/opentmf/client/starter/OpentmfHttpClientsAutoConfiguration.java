package org.opentmf.client.starter;

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
    org.opentmf.client.starter.reactive.ReactiveClientRegistrar.class,
    org.opentmf.client.starter.reactive.ReactiveLogbookAutoConfiguration.class,
    org.opentmf.client.starter.rest.RestClientRegistrar.class,
    org.opentmf.client.starter.rest.ApacheRestTemplateFactory.class,
    org.opentmf.client.starter.rest.JdkRestTemplateFactory.class,
    org.opentmf.client.starter.rest.RestLogbookAutoConfiguration.class
})
public class OpentmfHttpClientsAutoConfiguration {

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
}
