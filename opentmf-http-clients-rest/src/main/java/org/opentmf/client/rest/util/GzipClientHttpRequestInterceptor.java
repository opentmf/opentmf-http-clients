package org.opentmf.client.rest.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import lombok.Generated;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * A {@link ClientHttpRequestInterceptor} that adds {@code Accept-Encoding: gzip} to outgoing
 * requests and transparently decompresses gzipped responses.
 *
 * <p>Must be added <b>before</b> the Logbook interceptor so that Logbook logs
 * decompressed (readable) content.</p>
 */
@NullMarked
public final class GzipClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  @Generated
  private GzipClientHttpRequestInterceptor() {
  }

  private static final GzipClientHttpRequestInterceptor INSTANCE =
      new GzipClientHttpRequestInterceptor();

  public static GzipClientHttpRequestInterceptor instance() {
    return INSTANCE;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");
    var response = execution.execute(request, body);
    if (isGzipped(response)) {
      return new GzipClientHttpResponse(response);
    }
    return response;
  }

  private static boolean isGzipped(ClientHttpResponse response) {
    var encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
    return "gzip".equalsIgnoreCase(encoding);
  }

  private static class GzipClientHttpResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private InputStream decompressedBody;

    GzipClientHttpResponse(ClientHttpResponse delegate) {
      this.delegate = delegate;
    }

    @Override
    public InputStream getBody() throws IOException {
      if (decompressedBody == null) {
        decompressedBody = new GZIPInputStream(delegate.getBody());
      }
      return decompressedBody;
    }

    @Override
    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    @Override
    public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
      return delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return delegate.getStatusText();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
