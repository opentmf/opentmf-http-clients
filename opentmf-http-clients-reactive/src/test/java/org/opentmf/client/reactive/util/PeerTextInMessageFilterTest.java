package org.opentmf.client.reactive.util;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The {@code WebClient} error-wrapping filter carries as much of the peer's text in the message
 * as its {@link PeerTextInMessage} allows; the no-argument filter and {@code null} keep today's
 * message. Everything else on the exception is the same in every value.
 */
class PeerTextInMessageFilterTest {

  private static final String DETAIL = "D".repeat(3000);
  private static final String BODY = "{\"detail\":\"" + DETAIL + "\",\"title\":\"Busy\"}";
  private static final String TODAY = DETAIL + " — Busy";
  private static final String CAPPED = DETAIL.substring(0, 2000) + "...";

  static Stream<Arguments> filters() {
    return Stream.of(
        Arguments.of("no-arg", TODAY,
            (Supplier<ExchangeFilterFunction>) WebClientConfigUtil::errorWrappingFilter),
        Arguments.of("null", TODAY,
            (Supplier<ExchangeFilterFunction>) () ->
                WebClientConfigUtil.errorWrappingFilter((PeerTextInMessage) null)),
        Arguments.of("WHOLE", TODAY, (Supplier<ExchangeFilterFunction>) () ->
            WebClientConfigUtil.errorWrappingFilter(PeerTextInMessage.WHOLE)),
        Arguments.of("CAPPED", CAPPED, (Supplier<ExchangeFilterFunction>) () ->
            WebClientConfigUtil.errorWrappingFilter(PeerTextInMessage.CAPPED)),
        Arguments.of("NONE", null, (Supplier<ExchangeFilterFunction>) () ->
            WebClientConfigUtil.errorWrappingFilter(PeerTextInMessage.NONE)));
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("cells")
  void theMessageFollowsTheValue_andTheRestIsKept(String name, String peerText,
      HttpStatus status, Supplier<ExchangeFilterFunction> filter) {
    String expected = status.value() + " " + status.getReasonPhrase();
    expected = "HTTP " + expected + (peerText == null ? "" : ": " + peerText);
    var request = ClientRequest.create(HttpMethod.GET, URI.create("http://peer/x")).build();
    var answer = ClientResponse.create(status)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(HttpHeaders.RETRY_AFTER, "1")
        .header("X-Peer-Trace", "kept")
        .body(BODY)
        .build();

    String message = expected;
    StepVerifier.create(filter.get().filter(request, r -> Mono.just(answer)))
        .expectErrorSatisfies(failure -> {
          assertThat(failure).as(name).isInstanceOf(OpenTmfClientResponseException.class);
          assertThat(failure instanceof OpenTmfClientNotFoundException)
              .isEqualTo(status == HttpStatus.NOT_FOUND);
          var e = (OpenTmfClientResponseException) failure;
          assertThat(e.getMessage()).isEqualTo(message);
          assertThat(e.getRawStatusCode()).isEqualTo(status.value());
          assertThat(e.getResponseBody()).isEqualTo(BODY);
          assertThat(requireNonNull(e.getHeaders()).getFirst("X-Peer-Trace")).isEqualTo("kept");
          assertThat(e.getRetryAfter())
              .isEqualTo(status == HttpStatus.SERVICE_UNAVAILABLE ? Duration.ofSeconds(1) : null);
        })
        .verify(Duration.ofSeconds(5));
  }

  static Stream<Arguments> cells() {
    return filters().flatMap(f -> Stream.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.NOT_FOUND)
        .map(status -> Arguments.of(f.get()[0], f.get()[1], status, f.get()[2])));
  }
}
