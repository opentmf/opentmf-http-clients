package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.opentmf.client.rest.util.RestTemplateUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Integration tests that prove {@link RestClient} works with {@link RestTemplateUtil} helper
 * methods and with the {@link OpenTmfRestClientStatusHandler} error handler.
 */
class RestClientUtilIT {

  private static final String API_PATH = "/api/catalog";
  private static final Duration RETRY_WAIT = Duration.ofMillis(10);

  private final RestClient restClient = RestClient.builder()
      .baseUrl(BASE_URL)
      .defaultStatusHandler(HttpStatusCode::isError,
          OpenTmfRestClientStatusHandler.errorHandler())
      .build();

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  @Test
  void restClient_get_success() {
    get(API_PATH, 1, "\"hello\"", HttpStatus.OK);

    var result = restClient.get().uri(API_PATH).retrieve().body(String.class);
    assertThat(result).isEqualTo("\"hello\"");
  }

  @Test
  void restClient_404_throwsNotFoundException() {
    get(API_PATH, 1, "{\"detail\":\"not found\"}", HttpStatus.NOT_FOUND);

    assertThatThrownBy(() -> restClient.get().uri(API_PATH).retrieve().body(String.class))
        .isInstanceOf(OpenTmfClientNotFoundException.class)
        .satisfies(ex -> {
          var e = (OpenTmfClientNotFoundException) ex;
          assertThat(e.getRawStatusCode()).isEqualTo(404);
          assertThat(e.getResponseBody()).contains("not found");
        });
  }

  @Test
  void restClient_500_throwsClientResponseException() {
    get(API_PATH, 1, "{\"error\":\"internal\"}", HttpStatus.INTERNAL_SERVER_ERROR);

    assertThatThrownBy(() -> restClient.get().uri(API_PATH).retrieve().body(String.class))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .isNotInstanceOf(OpenTmfClientNotFoundException.class)
        .satisfies(ex -> {
          var e = (OpenTmfClientResponseException) ex;
          assertThat(e.getRawStatusCode()).isEqualTo(500);
        });
  }

  @Test
  void restClient_emptyBody_throwsExceptionWithStatusMessage() {
    get(API_PATH, 1, "", HttpStatus.BAD_REQUEST);

    assertThatThrownBy(() -> restClient.get().uri(API_PATH).retrieve().body(String.class))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(ex -> {
          var e = (OpenTmfClientResponseException) ex;
          assertThat(e.getRawStatusCode()).isEqualTo(400);
          assertThat(e.getMessage()).contains("400");
        });
  }

  @Test
  void executeWithRetry_succeedsOnFirstAttempt() {
    get(API_PATH, 1, "\"ok\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"ok\"");
  }

  @Test
  void executeWithRetry_succeedsAfterRetryableFailures() {
    get(API_PATH, 5, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    get(API_PATH, 1, "\"recovered\"", HttpStatus.OK);

    String result = RestTemplateUtil.executeWithRetry(
        () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
        3, RETRY_WAIT);

    assertThat(result).isEqualTo("\"recovered\"");
  }

  @Test
  void executeWithRetry_failsImmediatelyOnNonRetryableError() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
            3, RETRY_WAIT))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(ex -> assertThat(((OpenTmfClientResponseException) ex).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void executeWithRetry_exhaustsRetriesAndThrows() {
    get(API_PATH, 10, "unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    assertThatThrownBy(() ->
        RestTemplateUtil.executeWithRetry(
            () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
            3, RETRY_WAIT))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(ex -> assertThat(((OpenTmfClientResponseException) ex).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void emptyOn404_returnsEmptyOnNotFound() {
    get(API_PATH, 1, "{\"msg\":\"no such resource\"}", HttpStatus.NOT_FOUND);

    Optional<String> result = RestTemplateUtil.emptyOn404(
        () -> restClient.get().uri(API_PATH).retrieve().body(String.class));

    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_returnsValueOnSuccess() {
    get(API_PATH, 1, "\"found\"", HttpStatus.OK);

    Optional<String> result = RestTemplateUtil.emptyOn404(
        () -> restClient.get().uri(API_PATH).retrieve().body(String.class));

    assertThat(result).contains("\"found\"");
  }

  @Test
  void emptyOn404_rethrowsOnOtherErrors() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.emptyOn404(
            () -> restClient.get().uri(API_PATH).retrieve().body(String.class)))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void emptyOn_returnsEmptyOnMatchingStatus() {
    get(API_PATH, 1, "gone", HttpStatus.GONE);

    Optional<String> result = RestTemplateUtil.emptyOn(
        () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
        HttpStatus.NOT_FOUND, HttpStatus.GONE);

    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn_rethrowsOnNonMatchingStatus() {
    get(API_PATH, 1, "forbidden", HttpStatus.FORBIDDEN);

    assertThatThrownBy(() ->
        RestTemplateUtil.emptyOn(
            () -> restClient.get().uri(API_PATH).retrieve().body(String.class),
            HttpStatus.NOT_FOUND, HttpStatus.GONE))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }
}
