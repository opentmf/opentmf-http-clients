package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.exception.BearerWebClientException;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.HttpClientUtil;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.opentmf.client.rest.util.RestTemplateUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

class RestTemplateUtilIT {

  private static final String API_PATH = "/api/catalog";
  private static final Duration RETRY_WAIT = Duration.ofMillis(10);

  private final RestTemplate restTemplate = new RestTemplate();

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  // --- handleError ---

  @Test
  void handleError_withResponseBody_createsExceptionWithMessage() {
    get(API_PATH, 1, "{\"error\":\"not found\"}", HttpStatus.NOT_FOUND);

    try {
      restTemplate.getForObject(BASE_URL + API_PATH, String.class);
      fail("Expected RestClientResponseException");
    } catch (RestClientResponseException thrown) {
      var ex = RestTemplateUtil.handleError(thrown, BearerWebClientException.class);
      assertThat(ex).isInstanceOf(BearerWebClientException.class);
      assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(ex.getRawStatusCode()).isEqualTo(404);
      assertThat(ex.getMessage()).contains("not found");
    }
  }

  @Test
  void handleError_withEmptyBody_createsExceptionWithoutMessage() {
    get(API_PATH, 1, "", HttpStatus.INTERNAL_SERVER_ERROR);

    try {
      restTemplate.getForObject(BASE_URL + API_PATH, String.class);
      fail("Expected RestClientResponseException");
    } catch (RestClientResponseException thrown) {
      var ex = RestTemplateUtil.handleError(thrown, OpenTmfClientResponseException.class);
      assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
      assertThat(ex.getMessage()).isNull();
    }
  }

  // --- shouldRetryOn ---

  @Test
  void shouldRetryOn_retryableRestClientException_returnsTrue() {
    get(API_PATH, 1, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    try {
      restTemplate.getForObject(BASE_URL + API_PATH, String.class);
      fail("Expected HttpServerErrorException");
    } catch (HttpServerErrorException thrown) {
      assertThat(RestTemplateUtil.shouldRetryOn(thrown)).isTrue();
    }
  }

  @Test
  void shouldRetryOn_nonRetryableRestClientException_returnsFalse() {
    get(API_PATH, 1, "bad request", HttpStatus.BAD_REQUEST);

    try {
      restTemplate.getForObject(BASE_URL + API_PATH, String.class);
      fail("Expected HttpClientErrorException");
    } catch (HttpClientErrorException thrown) {
      assertThat(RestTemplateUtil.shouldRetryOn(thrown)).isFalse();
    }
  }

  @Test
  void shouldRetryOn_openTmfExceptionWithRetryableStatus_returnsTrue() {
    var ex = new BearerWebClientException(HttpStatus.BAD_GATEWAY, "upstream error");
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_openTmfExceptionWithNonRetryableStatus_returnsFalse() {
    var ex = new BearerWebClientException(HttpStatus.FORBIDDEN, "access denied");
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_allRetryableStatusCodes() {
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.REQUEST_TIMEOUT))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.TOO_MANY_REQUESTS))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.INTERNAL_SERVER_ERROR))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.GATEWAY_TIMEOUT))).isTrue();
    assertThat(RestTemplateUtil.shouldRetryOn(
        new OpenTmfClientResponseException(HttpStatus.BANDWIDTH_LIMIT_EXCEEDED))).isTrue();
  }

  @Test
  void shouldRetryOn_unrelatedRuntimeException_returnsFalse() {
    assertThat(RestTemplateUtil.shouldRetryOn(new RuntimeException("oops"))).isFalse();
  }

  // --- executeWithRetry (Supplier) ---

  @Test
  void executeWithRetry_succeedsOnFirstAttempt() {
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"ok\"");
  }

  @Test
  void executeWithRetry_succeedsAfterRetryableFailures() {
    get(API_PATH, 2, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    get(API_PATH, 1, "\"recovered\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"recovered\"");
  }

  @Test
  void executeWithRetry_failsImmediatelyOnNonRetryableError() {
    get(API_PATH, 1, "bad request", HttpStatus.BAD_REQUEST);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
            3, RETRY_WAIT))
        .isInstanceOf(HttpClientErrorException.class)
        .satisfies(ex -> assertThat(((HttpClientErrorException) ex).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void executeWithRetry_exhaustsRetriesAndThrowsLastException() {
    get(API_PATH, 4, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
            3, RETRY_WAIT))
        .isInstanceOf(HttpServerErrorException.class)
        .satisfies(ex -> assertThat(((HttpServerErrorException) ex).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void executeWithRetry_withJitter_succeedsAfterRetries() {
    get(API_PATH, 1, "timeout", HttpStatus.GATEWAY_TIMEOUT);
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
        2, RETRY_WAIT, 0.5);

    assertThat(result).isEqualTo("\"ok\"");
  }

  // --- executeWithRetry (Runnable) ---

  @Test
  void executeWithRetry_runnable_succeedsAfterRetries() {
    get(API_PATH, 1, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    get(API_PATH, 1, "", HttpStatus.OK);

    RestTemplateUtil.executeWithRetry(
        () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
        2, RETRY_WAIT);
  }

  @Test
  void executeWithRetry_runnable_failsImmediatelyOnNonRetryable() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            (Runnable) () -> restTemplate.getForObject(BASE_URL + API_PATH, String.class),
            3, RETRY_WAIT))
        .isInstanceOf(HttpClientErrorException.class);
  }

  // --- handleError + executeWithRetry combined ---

  @Test
  void executeWithRetry_withHandleError_convertsToTypedException() {
    get(API_PATH, 1, "{\"error\":\"service down\"}", HttpStatus.BAD_REQUEST);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            () -> {
              try {
                return restTemplate.getForObject(BASE_URL + API_PATH, String.class);
              } catch (RestClientResponseException ex) {
                throw RestTemplateUtil.handleError(ex, BearerWebClientException.class);
              }
            },
            2, RETRY_WAIT))
        .isInstanceOf(BearerWebClientException.class)
        .satisfies(ex -> {
          var bex = (BearerWebClientException) ex;
          assertThat(bex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(bex.getMessage()).contains("service down");
        });
  }

  @Test
  void executeWithRetry_withHandleError_retriesOnRetryableTypedException() {
    get(API_PATH, 2, "gateway error", HttpStatus.BAD_GATEWAY);
    get(API_PATH, 1, "\"success\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> {
          try {
            return restTemplate.getForObject(BASE_URL + API_PATH, String.class);
          } catch (RestClientResponseException ex) {
            throw RestTemplateUtil.handleError(ex, BearerWebClientException.class);
          }
        },
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"success\"");
  }

  // --- Auto-wrapping via OpenTmfResponseErrorHandler ---

  private final RestTemplate autoWrappedRestTemplate = createAutoWrappedRestTemplate();

  private static RestTemplate createAutoWrappedRestTemplate() {
    var rt = new RestTemplate();
    rt.setErrorHandler(new OpenTmfResponseErrorHandler());
    return rt;
  }

  @Test
  void autoWrapping_404_throwsOpenTmfClientNotFoundException() {
    get(API_PATH, 1, "{\"message\":\"not found\"}", HttpStatus.NOT_FOUND);

    assertThatThrownBy(() ->
        autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
        .isInstanceOf(OpenTmfClientNotFoundException.class)
        .satisfies(ex -> {
          var nfe = (OpenTmfClientNotFoundException) ex;
          assertThat(nfe.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(nfe.getMessage()).contains("not found");
          assertThat(nfe.getResponseBody()).contains("not found");
        });
  }

  @Test
  void autoWrapping_500_throwsOpenTmfClientResponseException() {
    get(API_PATH, 1, "{\"error\":\"internal\"}", HttpStatus.INTERNAL_SERVER_ERROR);

    assertThatThrownBy(() ->
        autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .isNotInstanceOf(OpenTmfClientNotFoundException.class)
        .satisfies(ex -> {
          var oex = (OpenTmfClientResponseException) ex;
          assertThat(oex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
          assertThat(oex.getResponseBody()).contains("internal");
        });
  }

  @Test
  void autoWrapping_emptyBody_throwsExceptionWithStatusMessage() {
    get(API_PATH, 1, "", HttpStatus.BAD_REQUEST);

    assertThatThrownBy(() ->
        autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(ex -> {
          var oex = (OpenTmfClientResponseException) ex;
          assertThat(oex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
          assertThat(oex.getMessage()).contains("400");
          assertThat(oex.getResponseBody()).isNull();
        });
  }

  @Test
  void autoWrapping_retryWorksWithAutoWrappedExceptions() {
    get(API_PATH, 2, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"ok\"");
  }

  // --- emptyOn404 ---

  @Test
  void emptyOn404_returns_emptyOnNotFound() {
    get(API_PATH, 1, "{\"message\":\"no such resource\"}", HttpStatus.NOT_FOUND);

    Optional<String> result = RestTemplateUtil.emptyOn404(
        () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class));

    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_returnsValue_onSuccess() {
    get(API_PATH, 1, "\"found\"", HttpStatus.OK);

    Optional<String> result = RestTemplateUtil.emptyOn404(
        () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class));

    assertThat(result).contains("\"found\"");
  }

  @Test
  void emptyOn404_rethrows_onOtherErrors() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.emptyOn404(
            () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class)))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  // --- emptyOn ---

  @Test
  void emptyOn_returnsEmpty_onMatchingStatus() {
    get(API_PATH, 1, "gone", HttpStatus.GONE);

    Optional<String> result = RestTemplateUtil.emptyOn(
        () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
        HttpStatus.NOT_FOUND, HttpStatus.GONE);

    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn_rethrows_onNonMatchingStatus() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.emptyOn(
            () -> autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
            HttpStatus.NOT_FOUND, HttpStatus.GONE))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  // --- remap ---

  @Test
  void remap_convertsToSubclass() {
    get(API_PATH, 1, "{\"error\":\"auth failed\"}", HttpStatus.UNAUTHORIZED);

    assertThatThrownBy(() -> {
      try {
        autoWrappedRestTemplate.getForObject(BASE_URL + API_PATH, String.class);
      } catch (OpenTmfClientResponseException e) {
        throw HttpClientUtil.remap(e, BearerWebClientException.class);
      }
    }).isInstanceOf(BearerWebClientException.class)
        .satisfies(ex -> {
          var bex = (BearerWebClientException) ex;
          assertThat(bex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
          assertThat(bex.getMessage()).contains("auth failed");
        });
  }
}
