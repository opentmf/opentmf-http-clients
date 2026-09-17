package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class TransportFailuresTest {

  private static final URI TOKEN_URL = URI.create("https://idp.example.org/token");

  static Stream<Arguments> cases() {
    return Stream.of(
        // the finding's shape: Spring's extractor wrapper around the JDK stream's "closed"
        Arguments.of("body-stage closed stream", new RestClientException(
            "Error while extracting response for type [ObjectNode]",
            new IOException("closed", new EOFException("EOF reached while reading"))), true),
        Arguments.of("headers-stage EOF", new ResourceAccessException("I/O error on POST",
            new EOFException("EOF reached while reading")), true),
        Arguments.of("connection reset", new ResourceAccessException("I/O error on POST",
            new IOException("Connection reset")), true),
        Arguments.of("I/O timeout", new ResourceAccessException("I/O error on POST",
            new HttpTimeoutException("request timed out")), true),
        Arguments.of("bare IOException", new IOException("closed"), true),
        Arguments.of("status error from the token endpoint",
            new OpenTmfClientResponseException(HttpStatus.UNAUTHORIZED, "invalid_client"), false),
        Arguments.of("typed status error",
            new BearerTokenException(HttpStatus.SERVICE_UNAVAILABLE, "down"), false),
        Arguments.of("Spring status error (RestClient without the library's handler)",
            new HttpClientErrorException(HttpStatus.UNAUTHORIZED), false),
        Arguments.of("open circuit breaker", new OpenTmfClientResilienceException("idp",
            "Circuit breaker 'idp' is open", new IllegalStateException()), false),
        Arguments.of("already retried", new BearerTokenTransportException(TOKEN_URL, 2,
            new IOException("closed")), false),
        Arguments.of("malformed JSON (unchecked, no I/O cause)", new RestClientException(
            "Error while extracting response", new IllegalStateException("Unexpected token")),
            false),
        Arguments.of("plain runtime failure", new IllegalArgumentException("bad form"), false));
  }

  @ParameterizedTest(name = "{0} -> transport: {2}")
  @MethodSource("cases")
  void classifies(String label, Throwable throwable, boolean expected) {
    assertThat(TransportFailures.isTransportFailure(throwable)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "rootCause of {0}")
  @MethodSource("cases")
  void rootCause_isTheInnermost(String label, Throwable throwable, boolean ignored) {
    var root = TransportFailures.rootCause(throwable);
    assertThat(root.getCause()).isNull();
  }
}
