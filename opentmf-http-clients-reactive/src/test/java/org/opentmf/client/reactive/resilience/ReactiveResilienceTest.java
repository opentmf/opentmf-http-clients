package org.opentmf.client.reactive.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReactiveResilienceTest {

  private final ResilienceRegistries registries = new ResilienceRegistries();

  private static ClientProperties enabledProperties() {
    var properties = new ClientProperties();
    properties.getResilience().setEnabled(true);
    properties.getResilience().getCircuitBreaker().setSlidingWindowSize(4);
    properties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(4);
    return properties;
  }

  private static WebClient client(ExchangeFunction exchangeFunction) {
    return WebClient.builder().exchangeFunction(exchangeFunction).build();
  }

  private static Mono<ClientResponse> exchange(WebClient webClient) {
    return webClient.get().uri("http://test/api").exchangeToMono(Mono::just);
  }

  @Test
  void disabled_returnsSameInstance() {
    var webClient = client(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()));
    var decorated = ReactiveResilience.decorate(
        webClient, "off", new ClientProperties(), registries);
    assertThat(decorated).isSameAs(webClient);
  }

  @Test
  void enabledWithoutResilience4j_throwsWithGuidance() {
    var webClient = client(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build()));
    var properties = enabledProperties();
    assertThatThrownBy(() ->
        ReactiveResilience.decorate(webClient, "orphan", properties, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("resilience4j is not on the classpath");
  }

  @Test
  void successfulExchange_passesThrough_andRecordsSuccess() {
    var decorated = ReactiveResilience.decorate(
        client(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())),
        "success", enabledProperties(), registries);

    StepVerifier.create(exchange(decorated))
        .assertNext(response -> assertThat(response.statusCode()).isEqualTo(HttpStatus.OK))
        .verifyComplete();
    assertThat(registries.circuitBreaker("success", enabledProperties().getResilience())
        .getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
  }

  @Test
  void recordedFailures_openCircuit_thenFailFastWrapped() {
    var properties = enabledProperties();
    var calls = new AtomicInteger();
    var decorated = ReactiveResilience.decorate(
        client(request -> {
          calls.incrementAndGet();
          return Mono.error(
              new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE, "boom"));
        }),
        "storm", properties, registries);

    for (int i = 0; i < 4; i++) {
      StepVerifier.create(exchange(decorated))
          .expectError(OpenTmfClientResponseException.class)
          .verify();
    }

    StepVerifier.create(exchange(decorated))
        .expectErrorSatisfies(throwable -> assertThat(throwable)
            .isInstanceOf(OpenTmfClientResilienceException.class)
            .hasCauseInstanceOf(CallNotPermittedException.class))
        .verify();
    assertThat(calls).hasValue(4);
  }

  @Test
  void clientErrors_doNotOpenCircuit() {
    var properties = enabledProperties();
    var decorated = ReactiveResilience.decorate(
        client(request -> Mono.error(
            new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST, "caller bug"))),
        "caller-bugs", properties, registries);

    for (int i = 0; i < 6; i++) {
      StepVerifier.create(exchange(decorated))
          .expectError(OpenTmfClientResponseException.class)
          .verify();
    }
    assertThat(registries.circuitBreaker("caller-bugs", properties.getResilience()).getState())
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void fullBulkhead_rejectsWrapped() {
    var properties = enabledProperties();
    properties.getResilience().getBulkhead().setMaxConcurrentCalls(1);
    var decorated = ReactiveResilience.decorate(
        client(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())
            .delayElement(Duration.ofSeconds(2))),
        "crowded", properties, registries);

    var blocked = exchange(decorated).subscribe();
    try {
      StepVerifier.create(exchange(decorated))
          .expectErrorSatisfies(throwable -> assertThat(throwable)
              .isInstanceOf(OpenTmfClientResilienceException.class)
              .hasCauseInstanceOf(BulkheadFullException.class))
          .verify(Duration.ofSeconds(5));
    } finally {
      blocked.dispose();
    }
  }

  @Test
  void timeLimiter_timesOutSlowCalls() {
    var properties = enabledProperties();
    properties.getResilience().getTimeLimiter().setTimeoutDuration(Duration.ofMillis(100));
    var decorated = ReactiveResilience.decorate(
        client(request -> Mono.just(ClientResponse.create(HttpStatus.OK).build())
            .delayElement(Duration.ofSeconds(5))),
        "sluggish", properties, registries);

    StepVerifier.create(exchange(decorated))
        .expectError(TimeoutException.class)
        .verify(Duration.ofSeconds(5));
    assertThat(registries.circuitBreaker("sluggish", properties.getResilience())
        .getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
  }
}
