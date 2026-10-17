package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpResponse.response;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;

/**
 * Success answers a token endpoint can give that carry no usable token, shared by the sync and
 * the reactive token-path tests. Every body holds {@link #SECRET}, which no error may repeat.
 */
public final class UnusableTokenResponses {

  /** Stands for whatever else the body holds — a refresh token, an id token, a hint. */
  public static final String SECRET = "SECRET-BODY-TEXT";

  private UnusableTokenResponses() {
  }

  public static Stream<Arguments> cases() {
    return Stream.of(
        Arguments.of("no access_token", json("{\"token_type\":\"Bearer\",\"id_token\":\""
            + SECRET + "\"}"), 200, Reason.FIELD_MISSING),
        Arguments.of("access_token null", json("{\"access_token\":null,\"id_token\":\""
            + SECRET + "\"}"), 200, Reason.FIELD_MISSING),
        Arguments.of("access_token not a string", json("{\"access_token\":{\"value\":\""
            + SECRET + "\"}}"), 200, Reason.FIELD_NOT_A_STRING),
        Arguments.of("access_token blank", json("{\"access_token\":\"  \",\"id_token\":\""
            + SECRET + "\"}"), 200, Reason.FIELD_BLANK),
        Arguments.of("empty 200 body", response().withStatusCode(200)
            .withContentType(MediaType.APPLICATION_JSON), 200, Reason.EMPTY_BODY),
        Arguments.of("204 no content", response().withStatusCode(204), 204, Reason.EMPTY_BODY),
        Arguments.of("html body", response().withStatusCode(200)
            .withContentType(MediaType.HTML_UTF_8)
            .withBody("<html><body>" + SECRET + "</body></html>"), 200, Reason.NOT_A_JSON_OBJECT),
        Arguments.of("broken json", json("{\"access_token\": " + SECRET), 200,
            Reason.NOT_A_JSON_OBJECT),
        Arguments.of("json array", json("[\"" + SECRET + "\"]"), 200,
            Reason.NOT_A_JSON_OBJECT));
  }

  /** The named error: endpoint, status and field in the message, nothing of the body anywhere. */
  public static void assertNamedError(Throwable thrown, URI tokenUrl, int status, Reason reason) {
    assertThat(thrown).isInstanceOf(BearerTokenResponseException.class)
        .hasNoCause()
        .hasMessageContaining(tokenUrl.toString())
        .hasMessageContaining("(" + status + " ")
        .hasMessageContaining("'access_token'")
        .hasMessageNotContaining(SECRET);
    var error = (BearerTokenResponseException) thrown;
    assertThat(error.getTokenUrl()).isEqualTo(tokenUrl);
    assertThat(error.getRawStatusCode()).isEqualTo(status);
    assertThat(error.getTokenField()).isEqualTo("access_token");
    assertThat(error.getReason()).isEqualTo(reason);
    assertThat(error.getResponseBody()).isNull();
    assertThat(error.toString()).doesNotContain(SECRET);
  }

  private static HttpResponse json(String body) {
    return response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
        .withBody(body);
  }
}
