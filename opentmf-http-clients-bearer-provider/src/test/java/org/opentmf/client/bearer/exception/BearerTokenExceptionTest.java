package org.opentmf.client.bearer.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;

class BearerTokenExceptionTest {

  @Test
  void inheritsFromOpenTmfClientResponseException() {
    var ex = new BearerTokenException(HttpStatus.UNAUTHORIZED);
    assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(ex).isInstanceOf(RuntimeException.class);
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
}
