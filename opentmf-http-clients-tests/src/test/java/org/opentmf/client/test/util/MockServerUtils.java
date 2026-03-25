package org.opentmf.client.test.util;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.springframework.http.HttpStatus;

public class MockServerUtils {

  public static final ClientAndServer clientAndServer = new ClientAndServer();
  public static final String BASE_URL = "http://localhost:" + clientAndServer.getLocalPort();

  public static void resetMockServer() {
    if (clientAndServer.isRunning()) {
      clientAndServer.reset();
    }
  }

  public static void mock(String method, String path, int times, String responseBody,
      HttpStatus httpStatus) {
    clientAndServer
        .when(
            request()
                .withMethod(method)
                .withPath(path),
            Times.exactly(times))
        .respond(
            response()
                .withBody(responseBody)
                .withStatusCode(httpStatus.value()));
  }

  public static void get(String path, int times, String responseBody, HttpStatus httpStatus) {
    mock("GET", path, times, responseBody, httpStatus);
  }

  public static void post(String path, int times, String responseBody, HttpStatus httpStatus) {
    mock("POST", path, times, responseBody, httpStatus);
  }
}
