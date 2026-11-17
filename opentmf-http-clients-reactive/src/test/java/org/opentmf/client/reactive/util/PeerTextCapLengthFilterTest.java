package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The {@code WebClient} error-wrapping filter built from a client's {@link ClientProperties}
 * follows both its {@code peer-text-in-message} and its {@code peer-text-cap-length}; unset, it
 * gives the message of every released version. A length outside the bounds is refused when the
 * filter is built.
 */
class PeerTextCapLengthFilterTest {

  private static final String DETAIL = "D".repeat(3000);
  private static final String PLAIN = "P".repeat(3000);
  private static final String JSON = "{\"detail\":\"" + DETAIL + "\",\"title\":\"Busy\"}";
  private static final String PREFIX = "HTTP 503 Service Unavailable: ";

  private static ClientProperties properties(PeerTextInMessage value, Integer length) {
    var properties = new ClientProperties();
    if (value != null) {
      properties.setPeerTextInMessage(value);
    }
    if (length != null) {
      properties.setPeerTextCapLength(length);
    }
    return properties;
  }

  static Stream<Arguments> cells() {
    return Stream.of(
        Arguments.of("unset json", properties(null, null), JSON, PREFIX + DETAIL + " — Busy"),
        Arguments.of("unset plain", properties(null, null), PLAIN,
            PREFIX + PLAIN.substring(0, 2000) + "..."),
        Arguments.of("capped 300 json", properties(PeerTextInMessage.CAPPED, 300), JSON,
            PREFIX + DETAIL.substring(0, 300) + "..."),
        Arguments.of("whole 300 json", properties(PeerTextInMessage.WHOLE, 300), JSON,
            PREFIX + DETAIL + " — Busy"),
        Arguments.of("whole 300 plain", properties(PeerTextInMessage.WHOLE, 300), PLAIN,
            PREFIX + PLAIN.substring(0, 300) + "..."),
        Arguments.of("none 300 json", properties(PeerTextInMessage.NONE, 300), JSON,
            "HTTP 503 Service Unavailable"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void theMessageFollowsTheValueAndTheLength(String name, ClientProperties properties,
      String body, String message) {
    var request = ClientRequest.create(HttpMethod.GET, URI.create("http://peer/x")).build();
    var answer = ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.CONTENT_TYPE, body.startsWith("{") ? MediaType.APPLICATION_JSON_VALUE
            : MediaType.TEXT_PLAIN_VALUE)
        .body(body)
        .build();
    var filter = WebClientConfigUtil.errorWrappingFilter(properties);

    StepVerifier.create(filter.filter(request, r -> Mono.just(answer)))
        .expectErrorSatisfies(failure -> {
          assertThat(failure).as(name).isInstanceOf(OpenTmfClientResponseException.class);
          var e = (OpenTmfClientResponseException) failure;
          assertThat(e.getMessage()).isEqualTo(message);
          assertThat(e.getResponseBody()).isEqualTo(body);
        })
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void aLengthOutsideTheBounds_isRefusedWhenTheFilterIsBuilt() {
    var tooLong = properties(PeerTextInMessage.CAPPED, 100_001);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> WebClientConfigUtil.errorWrappingFilter(tooLong))
        .withMessageContaining("peer-text-cap-length");
  }
}
