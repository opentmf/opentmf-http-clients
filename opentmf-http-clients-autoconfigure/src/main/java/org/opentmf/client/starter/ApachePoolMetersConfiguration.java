package org.opentmf.client.starter;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link ApachePoolMeters} when Micrometer and Apache HttpClient 5 are on the classpath
 * and a {@link MeterRegistry} bean exists. The Apache factory then registers per-client pool
 * gauges on every client it builds.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "io.micrometer.core.instrument.MeterRegistry",
    "org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager"})
public class ApachePoolMetersConfiguration {

  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  public ApachePoolMeters opentmfApachePoolMeters(MeterRegistry meterRegistry) {
    return new ApachePoolMeters(meterRegistry);
  }
}
