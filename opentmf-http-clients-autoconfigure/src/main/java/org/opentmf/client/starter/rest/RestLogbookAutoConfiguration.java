package org.opentmf.client.starter.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor")
public class RestLogbookAutoConfiguration {

  @Bean
  @ConditionalOnBean(Logbook.class)
  RestLogbookSupport restLogbookSupport(Logbook logbook) {
    return new RestLogbookSupport(new LogbookClientHttpRequestInterceptor(logbook));
  }
}
