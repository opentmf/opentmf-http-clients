package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ResilienceProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.util.SyncClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * End-to-end resilience decoration: a 503 storm opens the circuit of the sync and reactive
 * clients configured with {@code resilience.enabled: true}, after which calls fail fast with
 * {@link OpenTmfClientResilienceException} and the retry utility refuses to retry them. The
 * breakers use a 60s open-state so the state cannot flip back mid-test; each test uses its own
 * client id, and the sync breaker is reset before each run, so breaker state never leaks
 * between tests.
 */
@SpringBootTest
class ResilienceIT {

  private static final String API_PATH = "/api/storm";

  @Autowired private ApplicationContext context;
  @Autowired private ResilienceRegistries resilienceRegistries;
  @Autowired private WebClient stormReactiveWebClient;

  @BeforeEach
  void setUp() {
    resetMockServer();
    // Both shapes of stormSync share one breaker: each run starts from a closed one.
    resilienceRegistries.circuitBreaker("stormSync", new ResilienceProperties()).reset();
  }

  /** The client's RestClient bean. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"stormSyncRestClient"})
  void syncClient_503Storm_opensCircuit_failsFast_andRetryRefuses(String beanName) {
    get(API_PATH, 10, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() ->
          SyncShapes.get(context, beanName, BASE_URL + API_PATH))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientResilienceException.class);
    }

    assertThatThrownBy(() ->
        SyncShapes.get(context, beanName, BASE_URL + API_PATH))
        .isInstanceOf(OpenTmfClientResilienceException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class)
        .satisfies(ex -> assertThat(((OpenTmfClientResilienceException) ex).getClientId())
            .isEqualTo("stormSync"));

    var attempts = new AtomicInteger();
    var wait = Duration.ofMillis(10);
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(() -> {
      attempts.incrementAndGet();
      return SyncShapes.get(context, beanName, BASE_URL + API_PATH);
    }, 3, wait))
        .isInstanceOf(OpenTmfClientResilienceException.class);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void reactiveClient_503Storm_opensCircuit_thenFailsFast() {
    get(API_PATH, 10, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    for (int i = 0; i < 4; i++) {
      StepVerifier.create(stormReactiveWebClient.get()
              .uri(BASE_URL + API_PATH).retrieve().bodyToMono(String.class))
          .expectErrorMatches(throwable -> throwable instanceof OpenTmfClientResponseException
              && !(throwable instanceof OpenTmfClientResilienceException))
          .verify(Duration.ofSeconds(10));
    }

    StepVerifier.create(stormReactiveWebClient.get()
            .uri(BASE_URL + API_PATH).retrieve().bodyToMono(String.class))
        .expectErrorSatisfies(throwable -> assertThat(throwable)
            .isInstanceOf(OpenTmfClientResilienceException.class)
            .hasCauseInstanceOf(CallNotPermittedException.class))
        .verify(Duration.ofSeconds(10));
  }
}
