package org.opentmf.client.bearer.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;

class BearerTokenExceptionTest {

  @Test
  void inheritsFromOpenTmfClientResponseException() {
    var ex = new BearerTokenException(HttpStatus.UNAUTHORIZED);
    assertThat(ex)
        .isInstanceOf(OpenTmfClientResponseException.class)
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void constructorWithStatusCode() {
    var ex = new BearerTokenException(HttpStatus.UNAUTHORIZED);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ex.getRawStatusCode()).isEqualTo(401);
    assertThat(ex.getMessage()).isNull();
  }

  @Test
  void constructorWithStatusCodeAndMessage() {
    var ex = new BearerTokenException(HttpStatus.FORBIDDEN, "Token expired");
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ex.getMessage()).isEqualTo("Token expired");
  }

  @Test
  void constructorWithCause() {
    var cause = new RuntimeException("network");
    var ex = new BearerTokenException(HttpStatus.BAD_GATEWAY, "upstream", cause);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(ex.getMessage()).isEqualTo("upstream");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  @Test
  void aStatusFailureOfTheTokenCall_namesTheEndpointStatusAndErrorCode_neverABody() {
    var ex = new BearerTokenException(URI.create("http://idp/token"), HttpStatus.UNAUTHORIZED,
        "invalid_client");
    assertThat(ex.getMessage())
        .isEqualTo("Bearer token request to http://idp/token answered 401 Unauthorized "
            + "(error=invalid_client)");
    assertThat(ex.getTokenUrl()).isEqualTo(URI.create("http://idp/token"));
    assertThat(ex.getOauthError()).isEqualTo("invalid_client");
    assertThat(ex.getResponseBody()).isNull();
  }

  @Test
  void withoutAnErrorCode_theMessageSaysTheStatusOnly() {
    var ex = new BearerTokenException(URI.create("http://idp/token"), HttpStatus.GONE, null);
    assertThat(ex.getMessage())
        .isEqualTo("Bearer token request to http://idp/token answered 410 Gone");
    assertThat(ex.getOauthError()).isNull();
  }

  @Test
  void theOlderConstructors_carryNoEndpoint() {
    var ex = new BearerTokenException(HttpStatus.FORBIDDEN, "Token expired");
    assertThat(ex.getTokenUrl()).isNull();
    assertThat(ex.getOauthError()).isNull();
  }

  @Test
  void anUnusableSuccessAnswer_isASibling_bothInTheResponseFamily() {
    var ex = new BearerTokenResponseException(URI.create("http://idp/token"), HttpStatus.OK,
        "access_token", Reason.FIELD_MISSING);
    assertThat(ex).isNotInstanceOf(BearerTokenException.class)
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(ex.getTokenUrl()).isEqualTo(URI.create("http://idp/token"));
  }
}
