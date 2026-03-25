package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.exception.BearerWebClientException;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.HttpClientUtil;
import org.opentmf.client.reactive.util.WebClientConfigUtil;
import org.opentmf.client.reactive.util.WebClientUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WebClientUtilIT {

  private static final String API_PATH = "/api/catalog";
  private static final Duration RETRY_WAIT = Duration.ofMillis(10);

  private final WebClient webClient = WebClient.create(BASE_URL);

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  // --- handleError ---

  @Test
  void handleError_withResponseBody_createsExceptionWithMessage() {
    get(API_PATH, 1, "{\"error\":\"not found\"}", HttpStatus.NOT_FOUND);

    var result = webClient.get()
        .uri(API_PATH)
        .exchangeToMono(response -> {
          if (response.statusCode().isError()) {
            return WebClientUtil.handleError(response, BearerWebClientException.class)
                .flatMap(ex -> Mono.<String>error(ex));
          }
          return response.bodyToMono(String.class);
        });

    StepVerifier.create(result)
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(BearerWebClientException.class);
          var bex = (BearerWebClientException) ex;
          assertThat(bex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(bex.getRawStatusCode()).isEqualTo(404);
          assertThat(bex.getMessage()).contains("not found");
        })
        .verify();
  }

  @Test
  void handleError_withEmptyBody_createsExceptionWithoutMessage() {
    get(API_PATH, 1, "", HttpStatus.INTERNAL_SERVER_ERROR);

    var result = webClient.get()
        .uri(API_PATH)
        .exchangeToMono(response -> {
          if (response.statusCode().isError()) {
            return WebClientUtil.handleError(response, OpenTmfClientResponseException.class)
                .flatMap(ex -> Mono.<String>error(ex));
          }
          return response.bodyToMono(String.class);
        });

    StepVerifier.create(result)
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
          var oex = (OpenTmfClientResponseException) ex;
          assertThat(oex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
          assertThat(oex.getMessage()).isNull();
        })
        .verify();
  }

  // --- shouldRetryOn ---

  @Test
  void shouldRetryOn_webClientResponseExceptionWithRetryableStatus_returnsTrue() {
    var ex = WebClientResponseException.create(
        HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable",
        null, null, null);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_webClientResponseExceptionWithNonRetryableStatus_returnsFalse() {
    var ex = WebClientResponseException.create(
        HttpStatus.BAD_REQUEST.value(), "Bad Request",
        null, null, null);
    assertThat(WebClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_openTmfExceptionWithRetryableStatus_returnsTrue() {
    var ex = new BearerWebClientException(HttpStatus.BAD_GATEWAY, "upstream error");
    assertThat(WebClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_openTmfExceptionWithNonRetryableStatus_returnsFalse() {
    var ex = new BearerWebClientException(HttpStatus.FORBIDDEN, "access denied");
    assertThat(WebClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_allRetryableStatusCodes() {
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.REQUEST_TIMEOUT))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.INTERNAL_SERVER_ERROR))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.GATEWAY_TIMEOUT))).isTrue();
    assertThat(WebClientUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.BANDWIDTH_LIMIT_EXCEEDED))).isTrue();
  }

  @Test
  void shouldRetryOn_unrelatedRuntimeException_returnsFalse() {
    assertThat(WebClientUtil.shouldRetryOn(new RuntimeException("oops"))).isFalse();
  }

  // --- retry ---

  @Test
  void retry_succeedsOnFirstAttempt() {
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    var result = webClient.get()
        .uri(API_PATH)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectNext("\"ok\"")
        .verifyComplete();
  }

  @Test
  void retry_succeedsAfterRetryableFailures() {
    get(API_PATH, 2, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    get(API_PATH, 1, "\"recovered\"", HttpStatus.OK);

    var result = webClient.get()
        .uri(API_PATH)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectNext("\"recovered\"")
        .verifyComplete();
  }

  @Test
  void retry_failsImmediatelyOnNonRetryableError() {
    get(API_PATH, 1, "bad request", HttpStatus.BAD_REQUEST);

    var result = webClient.get()
        .uri(API_PATH)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(WebClientResponseException.class);
          assertThat(((WebClientResponseException) ex).getStatusCode())
              .isEqualTo(HttpStatus.BAD_REQUEST);
        })
        .verify();
  }

  @Test
  void retry_exhaustsRetriesAndThrowsLastException() {
    get(API_PATH, 4, "gateway timeout", HttpStatus.GATEWAY_TIMEOUT);

    var result = webClient.get()
        .uri(API_PATH)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(WebClientResponseException.class);
          assertThat(((WebClientResponseException) ex).getStatusCode())
              .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        })
        .verify();
  }

  @Test
  void retry_withJitter_succeedsAfterRetries() {
    get(API_PATH, 1, "bad gateway", HttpStatus.BAD_GATEWAY);
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    var result = webClient.get()
        .uri(API_PATH)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(2, RETRY_WAIT, 0.5));

    StepVerifier.create(result)
        .expectNext("\"ok\"")
        .verifyComplete();
  }

  // --- handleError + retry combined ---

  @Test
  void handleError_withRetry_retriesOnRetryableTypedException() {
    get(API_PATH, 2, "gateway error", HttpStatus.BAD_GATEWAY);
    get(API_PATH, 1, "\"success\"", HttpStatus.OK);

    var result = webClient.get()
        .uri(API_PATH)
        .exchangeToMono(response -> {
          if (response.statusCode().isError()) {
            return WebClientUtil.handleError(response, BearerWebClientException.class)
                .flatMap(ex -> Mono.<String>error(ex));
          }
          return response.bodyToMono(String.class);
        })
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectNext("\"success\"")
        .verifyComplete();
  }

  @Test
  void handleError_withRetry_failsImmediatelyOnNonRetryableTypedException() {
    get(API_PATH, 1, "{\"error\":\"forbidden\"}", HttpStatus.FORBIDDEN);

    var result = webClient.get()
        .uri(API_PATH)
        .exchangeToMono(response -> {
          if (response.statusCode().isError()) {
            return WebClientUtil.handleError(response, BearerWebClientException.class)
                .flatMap(ex -> Mono.<String>error(ex));
          }
          return response.bodyToMono(String.class);
        })
        .retryWhen(WebClientUtil.retry(3, RETRY_WAIT));

    StepVerifier.create(result)
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(BearerWebClientException.class);
          assertThat(((BearerWebClientException) ex).getStatusCode())
              .isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(ex.getMessage()).contains("forbidden");
        })
        .verify();
  }

  // --- Auto-wrapping via ExchangeFilterFunction ---

  private final WebClient autoWrappedWebClient = WebClient.builder()
      .baseUrl(BASE_URL)
      .filter(WebClientConfigUtil.errorWrappingFilter())
      .build();

  @Test
  void autoWrapping_404_throwsOpenTmfClientNotFoundException() {
    get(API_PATH, 1, "{\"message\":\"not found\"}", HttpStatus.NOT_FOUND);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(OpenTmfClientNotFoundException.class);
          var nfe = (OpenTmfClientNotFoundException) ex;
          assertThat(nfe.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(nfe.getMessage()).contains("not found");
          assertThat(nfe.getResponseBody()).contains("not found");
        })
        .verify();
  }

  @Test
  void autoWrapping_500_throwsOpenTmfClientResponseException() {
    get(API_PATH, 1, "{\"error\":\"internal\"}", HttpStatus.INTERNAL_SERVER_ERROR);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
          assertThat(ex).isNotInstanceOf(OpenTmfClientNotFoundException.class);
          var oex = (OpenTmfClientResponseException) ex;
          assertThat(oex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
          assertThat(oex.getResponseBody()).contains("internal");
        })
        .verify();
  }

  @Test
  void autoWrapping_emptyBody_throwsExceptionWithStatusMessage() {
    get(API_PATH, 1, "", HttpStatus.BAD_REQUEST);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
          var oex = (OpenTmfClientResponseException) ex;
          assertThat(oex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(oex.getMessage()).contains("400");
        })
        .verify();
  }

  @Test
  void autoWrapping_retryWorksWithAutoWrappedExceptions() {
    get(API_PATH, 2, "bad gateway", HttpStatus.BAD_GATEWAY);
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .retryWhen(WebClientUtil.retry(3, RETRY_WAIT)))
        .expectNext("\"ok\"")
        .verifyComplete();
  }

  // --- emptyOn404 ---

  @Test
  void emptyOn404_returnsEmpty_onNotFound() {
    get(API_PATH, 1, "{\"message\":\"no such resource\"}", HttpStatus.NOT_FOUND);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .transform(WebClientUtil.emptyOn404()))
        .verifyComplete();
  }

  @Test
  void emptyOn404_returnsValue_onSuccess() {
    get(API_PATH, 1, "\"found\"", HttpStatus.OK);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .transform(WebClientUtil.emptyOn404()))
        .expectNext("\"found\"")
        .verifyComplete();
  }

  @Test
  void emptyOn404_rethrows_onOtherErrors() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .transform(WebClientUtil.emptyOn404()))
        .expectError(OpenTmfClientResponseException.class)
        .verify();
  }

  // --- emptyOn ---

  @Test
  void emptyOn_returnsEmpty_onMatchingStatus() {
    get(API_PATH, 1, "gone", HttpStatus.GONE);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .transform(WebClientUtil.emptyOn(HttpStatus.NOT_FOUND, HttpStatus.GONE)))
        .verifyComplete();
  }

  @Test
  void emptyOn_rethrows_onNonMatchingStatus() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .transform(WebClientUtil.emptyOn(HttpStatus.NOT_FOUND, HttpStatus.GONE)))
        .expectError(OpenTmfClientResponseException.class)
        .verify();
  }

  // --- remap with auto-wrapped exceptions ---

  @Test
  void remap_convertsToSubclass() {
    get(API_PATH, 1, "{\"error\":\"auth failed\"}", HttpStatus.UNAUTHORIZED);

    StepVerifier.create(
            autoWrappedWebClient.get().uri(API_PATH)
                .retrieve().bodyToMono(String.class)
                .onErrorMap(OpenTmfClientResponseException.class,
                    e -> HttpClientUtil.remap(e, BearerWebClientException.class)))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(BearerWebClientException.class);
          var bex = (BearerWebClientException) ex;
          assertThat(bex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
          assertThat(bex.getMessage()).contains("auth failed");
        })
        .verify();
  }
}
