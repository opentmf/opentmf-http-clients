package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Verifies that both synchronous error handlers carry response headers, the parsed
 * {@code Retry-After}, and the {@code Content-Type} charset onto the thrown exception. The error
 * path is the only place these are reachable — the response is closed before callers see it.
 */
class ErrorHandlerResponseDetailsTest {

  private final OpenTmfResponseErrorHandler restTemplateHandler = new OpenTmfResponseErrorHandler();

  private static ClientHttpResponse response(HttpStatusCode status, HttpHeaders headers,
      byte[] body) throws IOException {
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    when(response.getHeaders()).thenReturn(headers);
    when(response.getBody()).thenReturn(new ByteArrayInputStream(body));
    return response;
  }

  private static HttpHeaders headers(String name, String value) {
    var headers = new HttpHeaders();
    headers.set(name, value);
    return headers;
  }

  private OpenTmfClientResponseException catchFromRestTemplate(HttpStatusCode status,
      HttpHeaders headers, byte[] body) throws IOException {
    var response = response(status, headers, body);
    var thrown = catchThrowable(
        () -> restTemplateHandler.handleError(URI.create("/api/items"), HttpMethod.GET, response));
    assertThat(thrown).isInstanceOf(OpenTmfClientResponseException.class);
    return (OpenTmfClientResponseException) thrown;
  }

  private OpenTmfClientResponseException catchFromRestClient(HttpStatusCode status,
      HttpHeaders headers, byte[] body) throws IOException {
    var response = response(status, headers, body);
    var thrown = catchThrowable(
        () -> OpenTmfRestClientStatusHandler.handleError(mock(HttpRequest.class), response));
    assertThat(thrown).isInstanceOf(OpenTmfClientResponseException.class);
    return (OpenTmfClientResponseException) thrown;
  }

  @Test
  void restTemplateHandler_carriesHeaders() throws IOException {
    var ex = catchFromRestTemplate(HttpStatus.INTERNAL_SERVER_ERROR,
        headers("X-Request-Id", "req-42"), "boom".getBytes(StandardCharsets.UTF_8));

    assertThat(ex.getHeaders()).isNotNull();
    assertThat(ex.getHeaders().getFirst("X-Request-Id")).isEqualTo("req-42");
  }

  @Test
  void restTemplateHandler_carriesRetryAfter_onRetryableStatus() throws IOException {
    var ex = catchFromRestTemplate(HttpStatus.SERVICE_UNAVAILABLE,
        headers(HttpHeaders.RETRY_AFTER, "12"), new byte[0]);

    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(12));
  }

  @Test
  void restTemplateHandler_dropsRetryAfter_onNonRetryableStatus() throws IOException {
    var ex = catchFromRestTemplate(HttpStatus.FORBIDDEN,
        headers(HttpHeaders.RETRY_AFTER, "12"), new byte[0]);

    // The header is still visible; only the actionable value is withheld.
    assertThat(ex.getRetryAfter()).isNull();
    assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
  }

  @Test
  void restTemplateHandler_usesCharsetFromContentType() throws IOException {
    Charset iso = StandardCharsets.ISO_8859_1;
    var headers = new HttpHeaders();
    headers.setContentType(new MediaType("application", "json", iso));

    var ex = catchFromRestTemplate(HttpStatus.BAD_REQUEST, headers,
        "{\"detail\":\"café\"}".getBytes(iso));

    assertThat(ex.getMessage()).contains("café");
    assertThat(ex.getResponseBody()).contains("café");
  }

  @Test
  void restTemplateHandler_malformedContentType_doesNotMaskTheRealError() throws IOException {
    var headers = headers(HttpHeaders.CONTENT_TYPE, "not/a/valid/type;;;");

    var ex = catchFromRestTemplate(HttpStatus.BAD_GATEWAY, headers,
        "upstream down".getBytes(StandardCharsets.UTF_8));

    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(ex.getMessage()).contains("upstream down");
  }

  @Test
  void restClientHandler_carriesHeadersAndRetryAfter() throws IOException {
    var headers = headers(HttpHeaders.RETRY_AFTER, "5");
    headers.set("X-Request-Id", "req-99");

    var ex = catchFromRestClient(HttpStatus.TOO_MANY_REQUESTS, headers, new byte[0]);

    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
    assertThat(ex.getHeaders().getFirst("X-Request-Id")).isEqualTo("req-99");
  }

  @Test
  void restClientHandler_dropsRetryAfter_onNonRetryableStatus() throws IOException {
    var ex = catchFromRestClient(HttpStatus.CONFLICT,
        headers(HttpHeaders.RETRY_AFTER, "5"), new byte[0]);

    assertThat(ex.getRetryAfter()).isNull();
  }

  @Test
  void nullHeaders_doNotBreakTheHandler() throws IOException {
    // An error handler must never replace the server's error with an NPE of its own.
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(HttpStatus.BAD_GATEWAY);
    when(response.getHeaders()).thenReturn(null);
    when(response.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));

    var uri = URI.create("/api/items");

    assertThatThrownBy(() -> restTemplateHandler.handleError(uri, HttpMethod.GET, response))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(t -> assertThat(((OpenTmfClientResponseException) t).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY));
  }
}
