package org.opentmf.client.starter.rest;

import org.opentmf.client.starter.TokenCallLogbook;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor")
public class RestLogbookAutoConfiguration {

  /**
   * The Logbook interceptors of the REST clients, each behind a {@link BodyReadFailureGuard}: a
   * response body Logbook failed to read reaches the caller as that failure, never as an end of
   * stream.
   */
  @Bean
  RestLogbookSupport restLogbookSupport(ObjectProvider<Logbook> logbookProvider) {
    Logbook logbook = logbookProvider.getIfAvailable(Logbook::create);
    return new RestLogbookSupport(
        new BodyReadFailureGuard(new LogbookClientHttpRequestInterceptor(logbook)),
        new BodyReadFailureGuard(
            new LogbookClientHttpRequestInterceptor(new TokenCallLogbook(logbook))));
  }
}
