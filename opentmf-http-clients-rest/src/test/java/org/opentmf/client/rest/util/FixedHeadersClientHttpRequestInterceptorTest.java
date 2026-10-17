package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;

class FixedHeadersClientHttpRequestInterceptorTest {

  @Test
  void setsTheFixedHeaders_theRequestHasNotSet() throws IOException {
    var interceptor = new FixedHeadersClientHttpRequestInterceptor(
        Map.of("x-api-key", "KEY-A", "X-Other", "o"));
    var request = new StubRequest();

    interceptor.intercept(request, new byte[0], (req, body) -> null);

    assertThat(request.getHeaders().get("x-api-key")).containsExactly("KEY-A");
    assertThat(request.getHeaders().get("X-Other")).containsExactly("o");
  }

  @Test
  void perRequestHeadersWin() throws IOException {
    var interceptor = new FixedHeadersClientHttpRequestInterceptor(
        Map.of("Content-Type", "application/json"));
    var request = new StubRequest();
    request.getHeaders().set("content-type", "application/x-www-form-urlencoded");

    interceptor.intercept(request, new byte[0], (req, body) -> null);

    assertThat(request.getHeaders().get("Content-Type"))
        .containsExactly("application/x-www-form-urlencoded");
  }

  @Test
  void readsTheMapOnEveryRequest() throws IOException {
    var fixedHeaders = new LinkedHashMap<String, String>();
    var interceptor = new FixedHeadersClientHttpRequestInterceptor(fixedHeaders);
    fixedHeaders.put("x-late", "added after construction");
    var request = new StubRequest();

    interceptor.intercept(request, new byte[0], (req, body) -> null);

    assertThat(request.getHeaders().getFirst("x-late")).isEqualTo("added after construction");
  }

  @Test
  void rejectsANullMap() {
    assertThatThrownBy(() -> new FixedHeadersClientHttpRequestInterceptor(null))
        .isInstanceOf(NullPointerException.class).hasMessage("fixedHeaders");
  }

  private static class StubRequest implements HttpRequest {
    private final HttpHeaders headers = new HttpHeaders();
    private final Map<String, Object> attributes = new HashMap<>();

    @Override public HttpMethod getMethod() { return HttpMethod.GET; }
    @Override public URI getURI() { return URI.create("http://test"); }
    @Override public HttpHeaders getHeaders() { return headers; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
  }
}
