package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.retry.RetryBackoffSpec;

class WebClientUtilTest {

  @Test
  void shouldRetryOn_retryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_retryableWebClientException() {
    var ex = WebClientResponseException.create(503, "Service Unavailable", null, null, null);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableWebClientException() {
    var ex = WebClientResponseException.create(401, "Unauthorized", null, null, null);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_nonHttpException() {
    assertThat(WebClientUtil.shouldRetryOn(new RuntimeException("network"))).isFalse();
  }

  @Test
  void retry_returnsRetrySpec() {
    RetryBackoffSpec spec = WebClientUtil.retry(3, Duration.ofMillis(10));
    assertThat(spec).isNotNull();
  }

  @Test
  void retry_withJitter() {
    RetryBackoffSpec spec = WebClientUtil.retry(3, Duration.ofMillis(10), 0.5);
    assertThat(spec).isNotNull();
  }

  @Test
  void emptyOn404_convertsNotFoundToEmpty() {
    Mono<String> mono = Mono.<String>error(
            new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND))
        .transform(WebClientUtil.emptyOn404());

    StepVerifier.create(mono)
        .verifyComplete();
  }

  @Test
  void emptyOn404_passesThroughOtherErrors() {
    Mono<String> mono = Mono.<String>error(
            new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST))
        .transform(WebClientUtil.emptyOn404());

    StepVerifier.create(mono)
        .expectError(OpenTmfClientResponseException.class)
        .verify();
  }

  @Test
  void emptyOn404_passesThroughValues() {
    Mono<String> mono = Mono.just("found")
        .transform(WebClientUtil.emptyOn404());

    StepVerifier.create(mono)
        .expectNext("found")
        .verifyComplete();
  }

  @Test
  void emptyOn_convertsMatchingStatusToEmpty() {
    Mono<String> mono = Mono.<String>error(
            new OpenTmfClientResponseException(HttpStatus.GONE))
        .transform(WebClientUtil.emptyOn(HttpStatus.NOT_FOUND, HttpStatus.GONE));

    StepVerifier.create(mono)
        .verifyComplete();
  }

  @Test
  void emptyOn_rethrowsNonMatchingStatus() {
    Mono<String> mono = Mono.<String>error(
            new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST))
        .transform(WebClientUtil.emptyOn(HttpStatus.NOT_FOUND));

    StepVerifier.create(mono)
        .expectError(OpenTmfClientResponseException.class)
        .verify();
  }

  @Test
  void emptyOn_passesThroughValues() {
    Mono<String> mono = Mono.just("ok")
        .transform(WebClientUtil.emptyOn(HttpStatus.NOT_FOUND));

    StepVerifier.create(mono)
        .expectNext("ok")
        .verifyComplete();
  }

  @Test
  void handleError_withBody() {
    var clientResponse = mockClientResponse(HttpStatus.INTERNAL_SERVER_ERROR);
    when(clientResponse.bodyToMono(String.class))
        .thenReturn(Mono.just("server error detail"));

    StepVerifier.create(WebClientUtil.handleError(clientResponse, OpenTmfClientResponseException.class))
        .expectNextMatches(t -> {
          assertThat(t).isInstanceOf(OpenTmfClientResponseException.class);
          assertThat(t.getMessage()).contains("server error detail");
          return true;
        })
        .verifyComplete();
  }

  @Test
  void handleError_emptyBody() {
    var clientResponse = mockClientResponse(HttpStatus.BAD_GATEWAY);
    when(clientResponse.bodyToMono(String.class))
        .thenReturn(Mono.empty());

    StepVerifier.create(WebClientUtil.handleError(clientResponse, OpenTmfClientResponseException.class))
        .expectError(OpenTmfClientResponseException.class)
        .verify();
  }

  @Test
  void handleError_notFoundExceptionType() {
    var clientResponse = mockClientResponse(HttpStatus.NOT_FOUND);
    when(clientResponse.bodyToMono(String.class))
        .thenReturn(Mono.just("not found"));

    StepVerifier.create(WebClientUtil.handleError(clientResponse, OpenTmfClientNotFoundException.class))
        .expectNextMatches(t -> {
          assertThat(t).isInstanceOf(OpenTmfClientNotFoundException.class);
          return true;
        })
        .verifyComplete();
  }

  private static ClientResponse mockClientResponse(HttpStatus status) {
    var response = mock(ClientResponse.class);
    when(response.statusCode()).thenReturn(status);
    var httpRequest = mock(org.springframework.http.HttpRequest.class);
    when(httpRequest.getMethod()).thenReturn(HttpMethod.GET);
    when(httpRequest.getURI()).thenReturn(URI.create("http://test/api"));
    when(response.request()).thenReturn(httpRequest);
    return response;
  }
}
