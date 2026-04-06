package org.opentmf.client.starter.rest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor")
public class RestLogbookAutoConfiguration {

  @Bean
  RestLogbookSupport restLogbookSupport(ObjectProvider<Logbook> logbookProvider) {
    Logbook logbook = logbookProvider.getIfAvailable(Logbook::create);
    return new RestLogbookSupport(new LogbookClientHttpRequestInterceptor(logbook));
  }
}
