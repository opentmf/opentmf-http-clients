package org.opentmf.client.starter.reactive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.zalando.logbook.netty.LogbookClientHandler")
public class ReactiveLogbookAutoConfiguration {

  @Bean
  @ConditionalOnBean(Logbook.class)
  ReactiveLogbookSupport reactiveLogbookSupport(Logbook logbook) {
    return new ReactiveLogbookSupport(
        conn -> conn.addHandlerLast(new LogbookClientHandler(logbook)));
  }
}
