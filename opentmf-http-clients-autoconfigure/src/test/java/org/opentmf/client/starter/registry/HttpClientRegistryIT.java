package org.opentmf.client.starter.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.starter.ApachePoolMeters;
import org.opentmf.client.starter.OpentmfHttpClientsAutoConfiguration;
import org.opentmf.client.starter.reactive.ReactiveClientRegistrar;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

class HttpClientRegistryIT {

  @Configuration(proxyBeanMethods = false)
  static class InfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(InfraConfig.class)
      .withPropertyValues(
          "opentmf.client-type=jdk",
          "opentmf.http-clients.static.base-url=http://localhost:9999");

  /**
   * Registry with zero grace period so retirements close synchronously and are assertable.
   */
  private static HttpClientRegistry immediateCloseRegistry(AssertableApplicationContext context) {
    return new HttpClientRegistry(
        context.getBean(RestClientRegistrar.class),
        context.getBean(ReactiveClientRegistrar.class),
        context.getBeanProvider(ResilienceRegistries.class),
        context.getBeanProvider(ApachePoolMeters.class),
        Duration.ZERO);
  }

  private static ClientProperties properties() {
    var properties = new ClientProperties();
    properties.setBaseUrl("http://localhost:9999");
    return properties;
  }

  @Test
  void registryBean_isExposed() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(HttpClientRegistry.class);
      assertThat(context.containsBean("opentmfHttpClientRegistry")).isTrue();
    });
  }

  @Test
  void getOrCreate_isIdempotent_untilReplace() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var first = registry.getOrCreate("onedms", ClientType.JDK, properties());
      var again = registry.getOrCreate("onedms", ClientType.JDK, properties());
      assertThat(again).isSameAs(first);
      assertThat(registry.names()).containsExactly("onedms");

      var replaced = registry.replace("onedms", ClientType.JDK, properties());
      assertThat(replaced).isNotSameAs(first);
      assertThat(replaced.restTemplate()).isNotSameAs(first.restTemplate());
      assertThat(registry.names()).containsExactly("onedms");
      registry.destroy();
    });
  }

  @Test
  void replace_closesTheRetiredApacheClient() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var old = registry.getOrCreate("dxl", ClientType.APACHE, properties());
      registry.replace("dxl", ClientType.APACHE, properties());

      var thrown = catchThrowable(() ->
          old.restTemplate().getForObject("http://localhost:9999/x", String.class));
      assertThat(thrown).hasStackTraceContaining("shut down");
      registry.destroy();
    });
  }

  @Test
  void evict_closesAndForgets() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var managed = registry.getOrCreate("gone", ClientType.APACHE, properties());
      assertThat(registry.evict("gone")).isTrue();
      assertThat(registry.evict("gone")).isFalse();
      assertThat(registry.names()).isEmpty();

      var thrown = catchThrowable(() ->
          managed.restTemplate().getForObject("http://localhost:9999/x", String.class));
      assertThat(thrown).hasStackTraceContaining("shut down");
      registry.destroy();
    });
  }

  @Test
  void typeMismatch_isRejectedClearly() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var nettyProperties = properties();
      assertThatThrownBy(() -> registry.getOrCreate("wrong", ClientType.NETTY, nettyProperties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("getOrCreateReactive");

      registry.getOrCreate("sync", ClientType.JDK, properties());
      var reactiveProperties = properties();
      assertThatThrownBy(() -> registry.getOrCreateReactive("sync", reactiveProperties))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("synchronous registry methods");
      registry.destroy();
    });
  }

  @Test
  void reactiveClient_fullLifecycle() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var managed = registry.getOrCreateReactive("asgw", properties());
      assertThat(managed.webClient()).isNotNull();
      assertThat(managed.tokenService()).isNotNull();
      assertThat(registry.getOrCreateReactive("asgw", properties())).isSameAs(managed);

      var replaced = registry.replaceReactive("asgw", properties());
      assertThat(replaced).isNotSameAs(managed);
      assertThat(registry.evict("asgw")).isTrue();
      registry.destroy();
    });
  }

  @Test
  void replace_resetsResilienceState() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var resilienceRegistries = context.getBean(ResilienceRegistries.class);
      var resilientProperties = properties();
      resilientProperties.getResilience().setEnabled(true);

      registry.getOrCreate("flaky", ClientType.JDK, resilientProperties);
      resilienceRegistries.circuitBreaker("flaky", resilientProperties.getResilience())
          .transitionToOpenState();

      registry.replace("flaky", ClientType.JDK, resilientProperties);

      assertThat(resilienceRegistries.circuitBreaker("flaky",
          resilientProperties.getResilience()).getState())
          .isEqualTo(CircuitBreaker.State.CLOSED);
      registry.destroy();
    });
  }

  private static <T> ObjectProvider<T> emptyProvider() {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return null;
      }

      @Override
      public T getIfAvailable() {
        return null;
      }
    };
  }

  @Test
  void missingBackends_failWithDependencyGuidance() {
    var registry = new HttpClientRegistry(null, null, emptyProvider(), emptyProvider(),
        Duration.ZERO);
    var clientProperties = properties();
    assertThatThrownBy(() -> registry.getOrCreate("s", ClientType.JDK, clientProperties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("starter-rest");
    assertThatThrownBy(() -> registry.getOrCreateReactive("r", clientProperties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("starter-reactive");
    registry.destroy();
  }

  @Test
  void apacheFactoryAbsent_failsWithClasspathGuidance() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(CloseableHttpClient.class))
        .run(context -> {
          var registry = immediateCloseRegistry(context);
          var clientProperties = properties();
          assertThatThrownBy(() ->
              registry.getOrCreate("dxl", ClientType.APACHE, clientProperties))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("httpclient5");
          registry.destroy();
        });
  }

  @Test
  void evictWithoutMetricsOrResilience_stillCloses() {
    contextRunner.run(context -> {
      var registry = new HttpClientRegistry(
          context.getBean(RestClientRegistrar.class),
          context.getBean(ReactiveClientRegistrar.class),
          emptyProvider(),
          emptyProvider(),
          Duration.ZERO);
      registry.getOrCreate("bare", ClientType.APACHE, properties());
      assertThat(registry.evict("bare")).isTrue();
      registry.destroy();
    });
  }

  @Test
  void getOrCreateSync_onReactiveName_isRejected() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      registry.getOrCreateReactive("mixed", properties());
      var clientProperties = properties();
      assertThatThrownBy(() -> registry.getOrCreate("mixed", ClientType.JDK, clientProperties))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("reactive registry methods");
      registry.destroy();
    });
  }

  @Test
  void replaceOnAbsentName_behavesLikeCreate() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var managed = registry.replace("fresh", ClientType.JDK, properties());
      assertThat(managed.restClient()).isNotNull();
      assertThat(registry.names()).containsExactly("fresh");
      registry.destroy();
    });
  }

  @Test
  void reactiveBearerClient_mockAndReal_buildTokenServices() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);

      var mockProperties = properties();
      var mockBearer = new BearerAuthConfig();
      mockBearer.setUseMock(true);
      mockProperties.setBearerAuth(mockBearer);
      var mockManaged = registry.getOrCreateReactive("mockBearer", mockProperties);
      assertThat(mockManaged.tokenService().getTokenType()).isEqualTo("Bearer");

      var realProperties = properties();
      var realBearer = new BearerAuthConfig();
      realBearer.setTokenUrl(URI.create("http://localhost:9999/token"));
      realBearer.setFormData(Map.of("grant_type", "client_credentials"));
      realProperties.setBearerAuth(realBearer);
      var realManaged = registry.getOrCreateReactive("realBearer", realProperties);
      assertThat(realManaged.tokenService()).isNotNull();
      assertThat(registry.evict("realBearer")).isTrue();
      registry.destroy();
    });
  }

  @Test
  void nonZeroGrace_destroyRunsThePendingCloser() {
    contextRunner.run(context -> {
      var registry = new HttpClientRegistry(
          context.getBean(RestClientRegistrar.class),
          context.getBean(ReactiveClientRegistrar.class),
          context.getBeanProvider(ResilienceRegistries.class),
          context.getBeanProvider(ApachePoolMeters.class),
          Duration.ofMinutes(5));
      var managed = registry.getOrCreate("delayed", ClientType.APACHE, properties());
      registry.evict("delayed");

      // still open — the closer is queued behind the 5m grace period
      var beforeDestroy = catchThrowable(() ->
          managed.restTemplate().getForObject("http://localhost:9999/x", String.class));
      assertThat(beforeDestroy).hasStackTraceContaining("Connection refused");

      registry.destroy();
      var afterDestroy = catchThrowable(() ->
          managed.restTemplate().getForObject("http://localhost:9999/x", String.class));
      assertThat(afterDestroy).hasStackTraceContaining("shut down");
    });
  }

  @Test
  void apacheDynamicClient_registersAndEvictsPoolGauges() {
    contextRunner.run(context -> {
      var registry = immediateCloseRegistry(context);
      var meterRegistry = context.getBean(MeterRegistry.class);

      registry.getOrCreate("pooled", ClientType.APACHE, properties());
      assertThat(meterRegistry.find("opentmf.client.pool.max")
          .tag("client", "pooled").gauge()).isNotNull();

      registry.evict("pooled");
      assertThat(meterRegistry.find("opentmf.client.pool.max")
          .tag("client", "pooled").gauge()).isNull();
      registry.destroy();
    });
  }
}
