package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.rest.util.GzipClientHttpRequestInterceptor;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.core.DefaultHttpLogFormatter;
import org.zalando.logbook.core.DefaultSink;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

/**
 * Guards the token client's read discipline through the library's real interceptor chain
 * (gzip + an actively logging Logbook): the token endpoint's response stream behaves like the
 * JDK HttpClient's — any read after {@code close()} throws {@code IOException("closed")} — and
 * the mint must parse the token without ever touching the stream after it was closed.
 */
class TokenResponseStreamGuardTest {

  private static final String TOKEN_JSON = "{\"access_token\":\"tok-1\",\"expires_in\":300}";

  /** A JDK-like body stream: no mark support, hard failure on read-after-close. */
  static final class JdkLikeStream extends InputStream {

    private final InputStream delegate;
    private boolean closed;
    final AtomicInteger readsAfterClose = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();

    JdkLikeStream(byte[] bytes) {
      this.delegate = new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() throws IOException {
      failIfClosed();
      return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      failIfClosed();
      return delegate.read(b, off, len);
    }

    private void failIfClosed() throws IOException {
      if (closed) {
        readsAfterClose.incrementAndGet();
        throw new IOException("closed");
      }
    }

    @Override
    public void close() {
      closed = true;
      closes.incrementAndGet();
    }
  }

  static final class StubResponse implements ClientHttpResponse {

    private final HttpHeaders headers = new HttpHeaders();
    private final JdkLikeStream body;

    StubResponse(byte[] bytes, boolean gzipped) {
      headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
      headers.setContentLength(bytes.length);
      if (gzipped) {
        headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
      }
      this.body = new JdkLikeStream(bytes);
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return HttpStatus.OK;
    }

    @Override
    public String getStatusText() {
      return "OK";
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public InputStream getBody() {
      return body;
    }

    @Override
    public void close() {
      body.close();
    }
  }

  @ParameterizedTest(name = "gzipped body: {0}")
  @ValueSource(booleans = {false, true})
  void mintNeverReadsAfterClose(boolean gzipped) throws IOException {
    var response = new StubResponse(gzipped ? gzip(TOKEN_JSON) : TOKEN_JSON.getBytes(
        StandardCharsets.UTF_8), gzipped);
    ClientHttpRequestFactory factory = (uri, method) -> new MockClientHttpRequest(method, uri) {
      @Override
      protected ClientHttpResponse executeInternal() {
        return response;
      }
    };

    var restTemplate = new RestTemplate(factory);
    restTemplate.setInterceptors(List.of(
        GzipClientHttpRequestInterceptor.instance(),
        new LogbookClientHttpRequestInterceptor(activeLogbook())));
    var restClient = RestClient.builder(restTemplate)
        .defaultStatusHandler(HttpStatusCode::isError,
            OpenTmfRestClientStatusHandler.errorHandler())
        .build();

    var config = new BearerAuthConfig();
    config.setTokenUrl(URI.create("http://idp.example.org/token"));
    var tokenClient = new SyncTokenClientImpl(restClient, config);

    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    var token = tokenClient.getToken(config.getTokenUrl(), form);

    assertThat(token.get("access_token").asString()).isEqualTo("tok-1");
    assertThat(response.body.readsAfterClose).hasValue(0);
    // closed by RestClient once the body is extracted (Logbook's wrapper closes the stream it
    // buffered and then the delegate, so the count is >= 1, never 0)
    assertThat(response.body.closes).hasPositiveValue();
  }

  /** A Logbook that always buffers and formats bodies, so the response is read for logging. */
  private static Logbook activeLogbook() {
    return Logbook.builder()
        .sink(new DefaultSink(new DefaultHttpLogFormatter(), new HttpLogWriter() {
          @Override
          public boolean isActive() {
            return true;
          }

          @Override
          public void write(Precorrelation precorrelation, String request) {
            // the guard is about how the body is read, not about what gets written
          }

          @Override
          public void write(Correlation correlation, String response) {
            // the guard is about how the body is read, not about what gets written
          }
        }))
        .build();
  }

  private static byte[] gzip(String text) throws IOException {
    var out = new ByteArrayOutputStream();
    try (var gz = new GZIPOutputStream(out)) {
      gz.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }
}
