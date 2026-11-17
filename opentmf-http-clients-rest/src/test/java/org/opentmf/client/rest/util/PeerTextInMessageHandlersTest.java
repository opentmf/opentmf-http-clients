package org.opentmf.client.rest.util;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The {@code RestTemplate} error handler and the {@code RestClient} status handler carry as much
 * of the peer's text in the message as their {@link PeerTextInMessage} allows; the no-argument
 * entry points and {@code null} keep today's message. Everything else on the exception is the
 * same in every value.
 */
class PeerTextInMessageHandlersTest {

  private static final String DETAIL = "D".repeat(3000);
  private static final String BODY = "{\"detail\":\"" + DETAIL + "\",\"title\":\"Busy\"}";
  private static final String TODAY = "HTTP 503 Service Unavailable: " + DETAIL + " — Busy";
  private static final String CAPPED =
      "HTTP 503 Service Unavailable: " + DETAIL.substring(0, 2000) + "...";
  private static final String NONE = "HTTP 503 Service Unavailable";

  interface Handler {
    void handle(ClientHttpResponse response) throws IOException;
  }

  static Stream<Arguments> handlers() {
    var request = mock(HttpRequest.class);
    var uri = URI.create("http://peer/x");
    return Stream.of(
        Arguments.of("RestTemplate no-arg", TODAY, (Handler) response ->
            new OpenTmfResponseErrorHandler().handleError(uri, HttpMethod.GET, response)),
        Arguments.of("RestTemplate null", TODAY, (Handler) response ->
            new OpenTmfResponseErrorHandler((PeerTextInMessage) null)
                .handleError(uri, HttpMethod.GET, response)),
        Arguments.of("RestTemplate WHOLE", TODAY, (Handler) response ->
            new OpenTmfResponseErrorHandler(PeerTextInMessage.WHOLE)
                .handleError(uri, HttpMethod.GET, response)),
        Arguments.of("RestTemplate CAPPED", CAPPED, (Handler) response ->
            new OpenTmfResponseErrorHandler(PeerTextInMessage.CAPPED)
                .handleError(uri, HttpMethod.GET, response)),
        Arguments.of("RestTemplate NONE", NONE, (Handler) response ->
            new OpenTmfResponseErrorHandler(PeerTextInMessage.NONE)
                .handleError(uri, HttpMethod.GET, response)),
        Arguments.of("RestClient no-arg", TODAY, (Handler) response ->
            OpenTmfRestClientStatusHandler.errorHandler().handle(request, response)),
        Arguments.of("RestClient null", TODAY, (Handler) response ->
            OpenTmfRestClientStatusHandler.errorHandler((PeerTextInMessage) null)
                .handle(request, response)),
        Arguments.of("RestClient WHOLE", TODAY, (Handler) response ->
            OpenTmfRestClientStatusHandler.errorHandler(PeerTextInMessage.WHOLE)
                .handle(request, response)),
        Arguments.of("RestClient CAPPED", CAPPED, (Handler) response ->
            OpenTmfRestClientStatusHandler.errorHandler(PeerTextInMessage.CAPPED)
                .handle(request, response)),
        Arguments.of("RestClient NONE", NONE, (Handler) response ->
            OpenTmfRestClientStatusHandler.errorHandler(PeerTextInMessage.NONE)
                .handle(request, response)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void theMessageFollowsTheValue_andTheRestIsKept(String name, String message, Handler handler) {
    var failure = catchThrowable(() -> handler.handle(response(HttpStatus.SERVICE_UNAVAILABLE)));

    assertThat(failure).as(name).isExactlyInstanceOf(OpenTmfClientResponseException.class);
    var e = (OpenTmfClientResponseException) failure;
    assertThat(e.getMessage()).isEqualTo(message);
    assertThat(e.getRawStatusCode()).isEqualTo(503);
    assertThat(e.getResponseBody()).isEqualTo(BODY);
    assertThat(requireNonNull(e.getHeaders()).getFirst("X-Peer-Trace")).isEqualTo("kept");
    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofSeconds(1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("handlers")
  void a404_staysTheNotFoundSubtype(String name, String message, Handler handler) {
    var failure = catchThrowable(() -> handler.handle(response(HttpStatus.NOT_FOUND)));

    assertThat(failure).as(name).isExactlyInstanceOf(OpenTmfClientNotFoundException.class);
    var e = (OpenTmfClientResponseException) failure;
    assertThat(e.getMessage())
        .isEqualTo(message.replace("503 Service Unavailable", "404 Not Found"));
    assertThat(e.getResponseBody()).isEqualTo(BODY);
  }

  private static ClientHttpResponse response(HttpStatus status) throws IOException {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.RETRY_AFTER, "1");
    headers.add("X-Peer-Trace", "kept");
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    when(response.getHeaders()).thenReturn(headers);
    when(response.getBody())
        .thenReturn(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
    return response;
  }
}
