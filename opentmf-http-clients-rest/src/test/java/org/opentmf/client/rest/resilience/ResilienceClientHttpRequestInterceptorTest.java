package org.opentmf.client.rest.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.model.ResilienceProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class ResilienceClientHttpRequestInterceptorTest {

  private static final Set<Integer> RECORDED = Set.of(500, 502, 503, 504);

  private final HttpRequest request = mock(HttpRequest.class);
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    circuitBreaker = CircuitBreaker.ofDefaults("test");
  }

  private ResilienceClientHttpRequestInterceptor interceptor(Bulkhead bulkhead) {
    return new ResilienceClientHttpRequestInterceptor("test", circuitBreaker, bulkhead, RECORDED);
  }

  private static ClientHttpRequestExecution respondingWith(HttpStatus status) throws IOException {
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    return (req, body) -> response;
  }

  @Test
  void successfulResponse_isRecordedAsSuccess() throws IOException {
    var response = interceptor(null).intercept(request, new byte[0], respondingWith(HttpStatus.OK));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void recordedStatus_isRecordedAsFailure_butResponseIsReturned() throws IOException {
    var response = interceptor(null)
        .intercept(request, new byte[0], respondingWith(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
  }

  @Test
  void clientError_isRecordedAsSuccess() throws IOException {
    interceptor(null).intercept(request, new byte[0], respondingWith(HttpStatus.BAD_REQUEST));

    assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void ioException_propagates_andIsRecordedAsFailure() {
    ClientHttpRequestExecution failing = (req, body) -> {
      throw new IOException("connection reset");
    };

    assertThatThrownBy(() -> interceptor(null).intercept(request, new byte[0], failing))
        .isInstanceOf(IOException.class);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
  }

  @Test
  void openCircuit_rejectsWithoutCallingExecution() {
    circuitBreaker.transitionToOpenState();
    var execution = mock(ClientHttpRequestExecution.class);
    var interceptor = interceptor(null);

    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
        .isInstanceOf(OpenTmfClientResilienceException.class)
        .hasCauseInstanceOf(CallNotPermittedException.class)
        .satisfies(ex -> assertThat(((OpenTmfClientResilienceException) ex).getClientId())
            .isEqualTo("test"));
    verifyNoInteractions(execution);
  }

  @Test
  void fullBulkhead_rejectsWithoutCallingExecution() {
    var bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).build());
    bulkhead.acquirePermission();
    var execution = mock(ClientHttpRequestExecution.class);
    var interceptor = interceptor(bulkhead);

    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
        .isInstanceOf(OpenTmfClientResilienceException.class)
        .hasCauseInstanceOf(BulkheadFullException.class);
    verifyNoInteractions(execution);
  }

  @Test
  void bulkheadPermission_isReleasedAfterEachCall() throws IOException {
    var bulkhead = Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).build());
    var interceptor = interceptor(bulkhead);

    interceptor.intercept(request, new byte[0], respondingWith(HttpStatus.OK));
    interceptor.intercept(request, new byte[0], respondingWith(HttpStatus.OK));

    assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
    assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
  }

  @Test
  void repeated503_opensCircuit_thenFailsFast() throws IOException {
    var properties = new ResilienceProperties();
    properties.getCircuitBreaker().setSlidingWindowSize(4);
    properties.getCircuitBreaker().setMinimumNumberOfCalls(4);
    circuitBreaker = new ResilienceRegistries().circuitBreaker("storm", properties);
    var interceptor = interceptor(null);
    var calls = new AtomicInteger();
    ClientHttpRequestExecution failing = (req, body) -> {
      calls.incrementAndGet();
      var response = mock(ClientHttpResponse.class);
      when(response.getStatusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
      return response;
    };

    for (int i = 0; i < 4; i++) {
      interceptor.intercept(request, new byte[0], failing);
    }

    assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], failing))
        .isInstanceOf(OpenTmfClientResilienceException.class);
    assertThat(calls).hasValue(4);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }
}
