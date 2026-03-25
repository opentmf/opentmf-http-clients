package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

class OpenTmfResponseErrorHandlerTest {

  private final OpenTmfResponseErrorHandler handler = new OpenTmfResponseErrorHandler();

  @Test
  void hasError_true_for4xx() throws IOException {
    var response = mockResponse(HttpStatus.BAD_REQUEST);
    assertThat(handler.hasError(response)).isTrue();
  }

  @Test
  void hasError_true_for5xx() throws IOException {
    var response = mockResponse(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(handler.hasError(response)).isTrue();
  }

  @Test
  void hasError_false_for2xx() throws IOException {
    var response = mockResponse(HttpStatus.OK);
    assertThat(handler.hasError(response)).isFalse();
  }

  @Test
  void hasError_false_for3xx() throws IOException {
    var response = mockResponse(HttpStatus.MOVED_PERMANENTLY);
    assertThat(handler.hasError(response)).isFalse();
  }

  @Test
  void handleError_404_throwsNotFoundException() throws IOException {
    try (var response = mockResponseWithBody(HttpStatus.NOT_FOUND, "Resource not found")) {
      assertThatThrownBy(() -> handler.handleError(
          URI.create("/api/items/42"), HttpMethod.GET, response))
          .isInstanceOf(OpenTmfClientNotFoundException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientNotFoundException) ex;
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getMessage()).contains("404");
            assertThat(e.getResponseBody()).isEqualTo("Resource not found");
          });
    }
  }

  @Test
  void handleError_500_throwsClientResponseException() throws IOException {
    try (var response = mockResponseWithBody(HttpStatus.INTERNAL_SERVER_ERROR, "Server error")) {
      assertThatThrownBy(() -> handler.handleError(
          URI.create("/api/items"), HttpMethod.POST, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientNotFoundException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(e.getMessage()).contains("500");
          });
    }
  }

  @Test
  void handleError_emptyBody() throws IOException {
    try (var response = mockResponseWithBody(HttpStatus.BAD_REQUEST, "")) {
      assertThatThrownBy(() -> handler.handleError(
          URI.create("/api"), HttpMethod.GET, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).contains("400");
          });
    }
  }

  @Test
  void handleError_jsonBody_extractsDetail() throws IOException {
    String jsonBody = """
        {"detail":"Validation failed for field 'name'","title":"Bad Request"}""";
    try (var response = mockResponseWithBody(HttpStatus.BAD_REQUEST, jsonBody)) {
      assertThatThrownBy(() -> handler.handleError(
          URI.create("/api"), HttpMethod.POST, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getMessage()).contains("Validation failed");
            assertThat(e.getResponseBody()).isEqualTo(jsonBody);
          });
    }
  }

  private ClientHttpResponse mockResponse(HttpStatusCode status) throws IOException {
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    return response;
  }

  private ClientHttpResponse mockResponseWithBody(HttpStatusCode status, String body)
      throws IOException {
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    when(response.getBody()).thenReturn(
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return response;
  }
}
