package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;

class OpenTmfRestClientStatusHandlerTest {

  @Test
  void errorHandler_returnsNonNullHandler() {
    assertThat(OpenTmfRestClientStatusHandler.errorHandler()).isNotNull();
  }

  @Test
  void handleError_404_throwsNotFoundException() throws IOException {
    var request = mock(HttpRequest.class);
    try (var response = mockResponse(HttpStatus.NOT_FOUND, "{\"detail\":\"not found\"}")) {
      assertThatThrownBy(() -> OpenTmfRestClientStatusHandler.handleError(request, response))
          .isInstanceOf(OpenTmfClientNotFoundException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientNotFoundException) ex;
            assertThat(e.getRawStatusCode()).isEqualTo(404);
            assertThat(e.getResponseBody()).isEqualTo("{\"detail\":\"not found\"}");
          });
    }
  }

  @Test
  void handleError_500_throwsClientResponseException() throws IOException {
    var request = mock(HttpRequest.class);
    try (var response = mockResponse(HttpStatus.INTERNAL_SERVER_ERROR, "{\"message\":\"server error\"}")) {
      assertThatThrownBy(() -> OpenTmfRestClientStatusHandler.handleError(request, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientNotFoundException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getRawStatusCode()).isEqualTo(500);
          });
    }
  }

  @Test
  void handleError_emptyBody() throws IOException {
    var request = mock(HttpRequest.class);
    try (var response = mockResponse(HttpStatus.BAD_GATEWAY, "")) {
      assertThatThrownBy(() -> OpenTmfRestClientStatusHandler.handleError(request, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .satisfies(ex -> {
            var e = (OpenTmfClientResponseException) ex;
            assertThat(e.getRawStatusCode()).isEqualTo(502);
          });
    }
  }

  @Test
  void handleError_400_throwsClientResponseException() throws IOException {
    var request = mock(HttpRequest.class);
    try (var response = mockResponse(HttpStatus.BAD_REQUEST, "bad request")) {
      assertThatThrownBy(() -> OpenTmfRestClientStatusHandler.handleError(request, response))
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientNotFoundException.class);
    }
  }

  private static ClientHttpResponse mockResponse(HttpStatus status, String body)
      throws IOException {
    var response = mock(ClientHttpResponse.class);
    when(response.getStatusCode()).thenReturn(status);
    when(response.getBody())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return response;
  }
}
