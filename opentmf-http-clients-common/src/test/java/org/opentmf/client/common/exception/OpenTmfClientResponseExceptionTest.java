package org.opentmf.client.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class OpenTmfClientResponseExceptionTest {

  @Test
  void constructorWithStatusCodeOnly() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ex.getRawStatusCode()).isEqualTo(400);
    assertThat(ex.getMessage()).isNull();
    assertThat(ex.getResponseBody()).isNull();
  }

  @Test
  void constructorWithStatusCodeAndMessage() {
    var ex = new OpenTmfClientResponseException(HttpStatus.FORBIDDEN, "Access denied");
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ex.getRawStatusCode()).isEqualTo(403);
    assertThat(ex.getMessage()).isEqualTo("Access denied");
    assertThat(ex.getResponseBody()).isNull();
  }

  @Test
  void constructorWithStatusCodeMessageAndBody() {
    var ex = new OpenTmfClientResponseException(
        HttpStatus.BAD_GATEWAY, "upstream error", "{\"error\":true}");
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(ex.getMessage()).isEqualTo("upstream error");
    assertThat(ex.getResponseBody()).isEqualTo("{\"error\":true}");
  }

  @Test
  void constructorWithCause() {
    var cause = new RuntimeException("root cause");
    var ex = new OpenTmfClientResponseException(
        HttpStatus.INTERNAL_SERVER_ERROR, "something broke", cause);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(ex.getMessage()).isEqualTo("something broke");
    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex.getResponseBody()).isNull();
  }

  @Test
  void customHttpStatusCode() {
    HttpStatusCode custom = HttpStatusCode.valueOf(999);
    var ex = new OpenTmfClientResponseException(custom, "custom");
    assertThat(ex.getRawStatusCode()).isEqualTo(999);
  }

  @Test
  void notFoundExceptionInheritsCorrectly() {
    var ex = new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND, "missing", "body");
    assertThat(ex)
        .isInstanceOf(OpenTmfClientResponseException.class)
        .isInstanceOf(RuntimeException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ex.getRawStatusCode()).isEqualTo(404);
    assertThat(ex.getMessage()).isEqualTo("missing");
    assertThat(ex.getResponseBody()).isEqualTo("body");
  }

  @Test
  void notFoundExceptionConstructors() {
    var ex1 = new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND);
    assertThat(ex1.getMessage()).isNull();

    var ex2 = new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND, "not found");
    assertThat(ex2.getMessage()).isEqualTo("not found");
  }
}
