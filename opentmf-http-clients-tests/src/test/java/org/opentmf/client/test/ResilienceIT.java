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
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.rest.util.SyncClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * End-to-end resilience decoration: a 503 storm opens the circuit of the sync and reactive
 * clients configured with {@code resilience.enabled: true}, after which calls fail fast with
 * {@link OpenTmfClientResilienceException} and the retry utility refuses to retry them. The
 * breakers use a 60s open-state so the state cannot flip back mid-test; each test uses its own
 * client id, so breaker state never leaks between tests.
 */
@SpringBootTest
class ResilienceIT {

  private static final String API_PATH = "/api/storm";

  @Autowired private RestTemplate stormSyncRestTemplate;
  @Autowired private WebClient stormReactiveWebClient;

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  @Test
  void syncClient_503Storm_opensCircuit_failsFast_andRetryRefuses() {
    get(API_PATH, 10, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() ->
          stormSyncRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientResilienceException.class);
    }

    assertThatThrownBy(() ->
        stormSyncRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
        .isInstanceOf(OpenTmfClientResilienceException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class)
        .satisfies(ex -> assertThat(((OpenTmfClientResilienceException) ex).getClientId())
            .isEqualTo("stormSync"));

    var attempts = new AtomicInteger();
    var wait = Duration.ofMillis(10);
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(() -> {
      attempts.incrementAndGet();
      return stormSyncRestTemplate.getForObject(BASE_URL + API_PATH, String.class);
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
