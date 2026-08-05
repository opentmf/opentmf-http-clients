package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.getWithHeader;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.reactive.util.WebClientUtil;
import org.opentmf.client.rest.util.SyncClientUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * End-to-end verification that a real HTTP response's {@code Retry-After} reaches the thrown
 * exception and shapes the retry schedule, and that Apache's transport-level automatic retries
 * are disabled so retry policy has exactly one owner across all backends.
 */
@SpringBootTest
class RetryAfterIT {

  private static final String API_PATH = "/api/retry-after";
  private static final Duration BACKOFF = Duration.ofMillis(10);
  private static final Duration CAP = Duration.ofSeconds(2);

  @Autowired private RestTemplate syncRetryAfterRestTemplate;
  @Autowired private RestTemplate apacheRetryRestTemplate;
  @Autowired private WebClient reactiveRetryAfterWebClient;

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  @Test
  void retryAfterReachesTheException() {
    getWithHeader(API_PATH, 1, "slow down", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");

    var ex = catchThrowableOfType(OpenTmfClientResponseException.class,
        () -> syncRetryAfterRestTemplate.getForObject(BASE_URL + API_PATH, String.class));

    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(1));
    assertThat(ex.getHeaders()).isNotNull();
    assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void retryAfterOnNonRetryableStatus_isNotCarried() {
    getWithHeader(API_PATH, 1, "denied", HttpStatus.FORBIDDEN,
        HttpHeaders.RETRY_AFTER, "1");

    var ex = catchThrowableOfType(OpenTmfClientResponseException.class,
        () -> syncRetryAfterRestTemplate.getForObject(BASE_URL + API_PATH, String.class));

    assertThat(ex.getRetryAfter()).isNull();
    assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void retryAfterLengthensTheWaitBetweenAttempts() {
    getWithHeader(API_PATH, 1, "slow down", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");
    get(API_PATH, 1, "recovered", HttpStatus.OK);

    long start = System.nanoTime();
    var body = SyncClientUtil.executeWithRetry(
        () -> syncRetryAfterRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(2));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(body).isEqualTo("recovered");
    // A 10ms backoff raised to the server's requested second.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(1_000);
  }

  @Test
  void retryAfterBeyondTheConfiguredCap_failsFast() {
    getWithHeader(API_PATH, 5, "come back tomorrow", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "3600");

    Supplier<String> call = () -> SyncClientUtil.executeWithRetry(
        () -> syncRetryAfterRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, BACKOFF, 0.0d, CAP);

    long start = System.nanoTime();
    assertThatThrownBy(call::get).isInstanceOf(OpenTmfClientResponseException.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isLessThan(5_000);
  }

  @Test
  void apacheClient_doesNotRetryAtTheTransportLevel() {
    // Apache's DefaultHttpRequestRetryStrategy would otherwise retry 503 once, transparently,
    // even though the caller opted into no retries at all. Only one response is mocked, so a
    // transport-level retry would consume it and change the observed error.
    getWithHeader(API_PATH, 1, "unavailable", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");
    get(API_PATH, 1, "second response", HttpStatus.OK);

    var ex = catchThrowableOfType(OpenTmfClientResponseException.class,
        () -> apacheRetryRestTemplate.getForObject(BASE_URL + API_PATH, String.class));

    // The caller sees the first 503 directly; the queued 200 is untouched.
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(apacheRetryRestTemplate.getForObject(BASE_URL + API_PATH, String.class))
        .isEqualTo("second response");
  }

  @Test
  void apacheClient_retriesOnlyWhenTheCallerOptsIn() {
    getWithHeader(API_PATH, 1, "unavailable", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");
    get(API_PATH, 1, "recovered", HttpStatus.OK);

    var body = SyncClientUtil.executeWithRetry(
        () -> apacheRetryRestTemplate.getForObject(BASE_URL + API_PATH, String.class),
        3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(5));

    assertThat(body).isEqualTo("recovered");
  }

  @Test
  void reactiveClient_carriesRetryAfterAndHeaders() {
    getWithHeader(API_PATH, 1, "slow down", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");

    var ex = catchThrowableOfType(OpenTmfClientResponseException.class,
        () -> reactiveRetryAfterWebClient.get().uri(BASE_URL + API_PATH)
            .retrieve().bodyToMono(String.class).block());

    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(1));
    assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void reactiveClient_lengthensTheWaitBetweenAttempts() {
    getWithHeader(API_PATH, 1, "slow down", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "1");
    get(API_PATH, 1, "recovered", HttpStatus.OK);

    long start = System.nanoTime();
    var body = reactiveRetryAfterWebClient.get().uri(BASE_URL + API_PATH)
        .retrieve().bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(2)))
        .block();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(body).isEqualTo("recovered");
    assertThat(elapsedMs).isGreaterThanOrEqualTo(1_000);
  }

  @Test
  void reactiveClient_retryAfterBeyondTheCap_failsFast() {
    getWithHeader(API_PATH, 5, "come back tomorrow", HttpStatus.SERVICE_UNAVAILABLE,
        HttpHeaders.RETRY_AFTER, "3600");

    var mono = reactiveRetryAfterWebClient.get().uri(BASE_URL + API_PATH)
        .retrieve().bodyToMono(String.class)
        .retryWhen(WebClientUtil.retry(3, BACKOFF, 0.0d, CAP));

    long start = System.nanoTime();
    assertThatThrownBy(mono::block).isInstanceOf(OpenTmfClientResponseException.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isLessThan(5_000);
  }
}
