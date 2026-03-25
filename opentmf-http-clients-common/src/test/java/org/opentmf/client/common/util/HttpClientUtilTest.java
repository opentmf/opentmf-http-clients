package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class HttpClientUtilTest {

  @ParameterizedTest
  @ValueSource(ints = {408, 429, 500, 502, 503, 504, 509})
  void isRetryableStatus_returnsTrue_forRetryableCodes(int code) {
    assertThat(HttpClientUtil.isRetryableStatus(HttpStatusCode.valueOf(code))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 301, 400, 401, 403, 404, 409, 422})
  void isRetryableStatus_returnsFalse_forNonRetryableCodes(int code) {
    assertThat(HttpClientUtil.isRetryableStatus(HttpStatusCode.valueOf(code))).isFalse();
  }

  @Test
  void isRetryableStatus_returnsFalse_forNull() {
    assertThat(HttpClientUtil.isRetryableStatus(null)).isFalse();
  }

  @Test
  void createException_withStatusCodeOnly() {
    var ex = HttpClientUtil.createException(
        HttpStatus.BAD_REQUEST, OpenTmfClientResponseException.class);
    assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void createException_withStatusCodeAndMessage() {
    var ex = HttpClientUtil.createException(
        HttpStatus.NOT_FOUND, "Resource not found", OpenTmfClientNotFoundException.class);
    assertThat(ex).isInstanceOf(OpenTmfClientNotFoundException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ex.getMessage()).isEqualTo("Resource not found");
  }

  @Test
  void createException_invalidClass_throws() {
    assertThatThrownBy(() -> HttpClientUtil.createException(
        HttpStatus.BAD_REQUEST, NoMatchingConstructorException.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required constructor");
  }

  @Test
  void remap_usesThreeArgConstructor() {
    var source = new OpenTmfClientResponseException(
        HttpStatus.BAD_GATEWAY, "upstream error", "{\"error\":true}");

    var remapped = HttpClientUtil.remap(source, OpenTmfClientNotFoundException.class);

    assertThat(remapped).isInstanceOf(OpenTmfClientNotFoundException.class);
    assertThat(remapped.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(remapped.getMessage()).isEqualTo("upstream error");
    assertThat(remapped.getResponseBody()).isEqualTo("{\"error\":true}");
  }

  @Test
  void remap_fallsBackToTwoArgConstructor_whenThreeArgMissing() {
    var source = new OpenTmfClientResponseException(
        HttpStatus.BAD_GATEWAY, "upstream error", "{\"error\":true}");

    var remapped = HttpClientUtil.remap(source, TwoArgOnlyException.class);

    assertThat(remapped).isInstanceOf(TwoArgOnlyException.class);
    assertThat(remapped.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(remapped.getMessage()).isEqualTo("upstream error");
  }

  @Test
  void remap_invalidClass_throws() {
    var source = new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY, "error");
    assertThatThrownBy(() -> HttpClientUtil.remap(source, NoMatchingConstructorException.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static class TwoArgOnlyException extends OpenTmfClientResponseException {
    public TwoArgOnlyException(HttpStatusCode statusCode, String message) {
      super(statusCode, message);
    }
  }

  static class NoMatchingConstructorException extends OpenTmfClientResponseException {
    NoMatchingConstructorException(String unrelated) {
      super(HttpStatus.INTERNAL_SERVER_ERROR, unrelated);
    }
  }
}
