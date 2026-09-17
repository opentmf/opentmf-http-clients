package org.opentmf.client.starter;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link TokenFetchMeters} when Micrometer is on the classpath and a
 * {@link MeterRegistry} bean exists. The registrars then hand every bearer token client a
 * counting listener; without the bean they hand out a no-op one.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public class TokenFetchMetersConfiguration {

  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  public TokenFetchMeters opentmfTokenFetchMeters(MeterRegistry meterRegistry) {
    return new TokenFetchMeters(meterRegistry);
  }
}
