package org.opentmf.client.starter.reactive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.zalando.logbook.netty.LogbookClientHandler")
public class ReactiveLogbookAutoConfiguration {

  @Bean
  ReactiveLogbookSupport reactiveLogbookSupport(ObjectProvider<Logbook> logbookProvider) {
    Logbook logbook = logbookProvider.getIfAvailable(Logbook::create);
    return new ReactiveLogbookSupport(
        conn -> conn.addHandlerLast(new LogbookClientHandler(logbook)));
  }
}
