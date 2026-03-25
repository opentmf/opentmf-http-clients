package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

class GzipClientHttpRequestInterceptorTest {

  @Test
  void setsAcceptEncodingGzip() throws IOException {
    var interceptor = GzipClientHttpRequestInterceptor.instance();
    var request = new StubRequest();
    try (var response = new StubResponse(HttpHeaders.EMPTY, "plain".getBytes(), HttpStatusCode.valueOf(200))) {
      interceptor.intercept(request, new byte[0], (req, body) -> response);
      assertThat(request.getHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING)).isEqualTo("gzip");
    }
  }

  @Test
  void passesThrough_whenResponseNotGzipped() throws IOException {
    var interceptor = GzipClientHttpRequestInterceptor.instance();
    var request = new StubRequest();
    var body = "hello".getBytes();
    try (var response = new StubResponse(HttpHeaders.EMPTY, body, HttpStatusCode.valueOf(200))) {
      try (var result = interceptor.intercept(request, new byte[0], (req, b) -> response)) {
        assertThat(result).isSameAs(response);
      }
    }
  }

  @Test
  void decompresses_whenResponseIsGzipped() throws IOException {
    var interceptor = GzipClientHttpRequestInterceptor.instance();
    var request = new StubRequest();
    var originalText = "compressed content";
    var gzipped = gzip(originalText.getBytes());

    var headers = new HttpHeaders();
    headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
    try (var response = new StubResponse(headers, gzipped, HttpStatusCode.valueOf(200))) {
      try (var result = interceptor.intercept(request, new byte[0], (req, b) -> response)) {
        var decompressed = new String(result.getBody().readAllBytes());
        assertThat(decompressed).isEqualTo(originalText);
      }
    }
  }

  @Test
  void instance_returnsSingleton() {
    assertThat(GzipClientHttpRequestInterceptor.instance())
        .isSameAs(GzipClientHttpRequestInterceptor.instance());
  }

  private static byte[] gzip(byte[] data) throws IOException {
    var baos = new ByteArrayOutputStream();
    try (var gos = new GZIPOutputStream(baos)) {
      gos.write(data);
    }
    return baos.toByteArray();
  }

  @NullMarked
  private static class StubRequest implements HttpRequest {
    private final HttpHeaders headers = new HttpHeaders();
    private final Map<String, Object> attributes = new HashMap<>();

    @Override public HttpMethod getMethod() { return HttpMethod.GET; }
    @Override public java.net.URI getURI() { return java.net.URI.create("http://test"); }
    @Override public HttpHeaders getHeaders() { return headers; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
  }

  @NullMarked
  private static class StubResponse implements ClientHttpResponse {
    private final HttpHeaders headers;
    private final byte[] body;
    private final HttpStatusCode status;

    StubResponse(HttpHeaders headers, byte[] body, HttpStatusCode status) {
      this.headers = headers;
      this.body = body;
      this.status = status;
    }

    @Override public HttpStatusCode getStatusCode() { return status; }
    @Override public String getStatusText() { return "OK"; }
    @Override public HttpHeaders getHeaders() { return headers; }
    @Override public InputStream getBody() { return new java.io.ByteArrayInputStream(body); }
    @Override public void close() {}
  }
}
