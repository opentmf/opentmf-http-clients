package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;

/**
 * The status handler built from a client's {@link ClientProperties} follows both its
 * {@code peer-text-in-message} and its {@code peer-text-cap-length}; unset, they give the message
 * of every released version. A length outside the bounds is refused when the handler is built.
 */
class PeerTextCapLengthHandlersTest {

  private static final String DETAIL = "D".repeat(3000);
  private static final String PLAIN = "P".repeat(3000);
  private static final String JSON = "{\"detail\":\"" + DETAIL + "\",\"title\":\"Busy\"}";
  private static final String PREFIX = "HTTP 503 Service Unavailable: ";

  interface Handler {
    void handle(ClientProperties properties, ClientHttpResponse response) throws IOException;
  }

  private static final Handler REST_CLIENT = (properties, response) ->
      OpenTmfRestClientStatusHandler.errorHandler(properties)
          .handle(mock(HttpRequest.class), response);

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
    var cells = Stream.<Arguments>builder();
    for (var entry : new Object[][] {{"RestClient", REST_CLIENT}}) {
      String name = (String) entry[0];
      Handler handler = (Handler) entry[1];
      cells.add(Arguments.of(name + " unset json", handler, properties(null, null), JSON,
          PREFIX + DETAIL + " — Busy"));
      cells.add(Arguments.of(name + " unset plain", handler, properties(null, null), PLAIN,
          PREFIX + PLAIN.substring(0, 2000) + "..."));
      cells.add(Arguments.of(name + " capped default json", handler,
          properties(PeerTextInMessage.CAPPED, null), JSON,
          PREFIX + DETAIL.substring(0, 2000) + "..."));
      cells.add(Arguments.of(name + " capped 300 json", handler,
          properties(PeerTextInMessage.CAPPED, 300), JSON,
          PREFIX + DETAIL.substring(0, 300) + "..."));
      cells.add(Arguments.of(name + " whole 300 json", handler,
          properties(PeerTextInMessage.WHOLE, 300), JSON, PREFIX + DETAIL + " — Busy"));
      cells.add(Arguments.of(name + " whole 300 plain", handler,
          properties(PeerTextInMessage.WHOLE, 300), PLAIN,
          PREFIX + PLAIN.substring(0, 300) + "..."));
      cells.add(Arguments.of(name + " none 300 json", handler,
          properties(PeerTextInMessage.NONE, 300), JSON, "HTTP 503 Service Unavailable"));
    }
    return cells.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cells")
  void theMessageFollowsTheValueAndTheLength(String name, Handler handler,
      ClientProperties properties, String body, String message) {
    var failure = catchThrowable(() -> handler.handle(properties, response(body)));

    assertThat(failure).as(name).isExactlyInstanceOf(OpenTmfClientResponseException.class);
    var e = (OpenTmfClientResponseException) failure;
    assertThat(e.getMessage()).isEqualTo(message);
    assertThat(e.getResponseBody()).isEqualTo(body);
  }

  @Test
  void aLengthOutsideTheBounds_isRefusedWhenTheHandlerIsBuilt() {
    var tooShort = properties(PeerTextInMessage.CAPPED, 99);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> OpenTmfRestClientStatusHandler.errorHandler(tooShort))
        .withMessageContaining("peer-text-cap-length");
  }

  private static ClientHttpResponse response(String body) throws IOException {
    var headers = new HttpHeaders();
    headers.setContentType(body.startsWith("{") ? MediaType.APPLICATION_JSON
        : MediaType.TEXT_PLAIN);
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(HttpStatus.SERVICE_UNAVAILABLE);
    when(response.getHeaders()).thenReturn(headers);
    when(response.getBody())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return response;
  }
}
