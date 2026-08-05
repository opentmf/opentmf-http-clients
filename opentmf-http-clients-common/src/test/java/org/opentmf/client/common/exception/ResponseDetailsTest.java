package org.opentmf.client.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.util.HttpClientUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Covers the error-path response details carried by {@link OpenTmfClientResponseException}:
 * headers and the parsed {@code Retry-After}.
 */
class ResponseDetailsTest {

  /** A consumer-style subclass declaring only the three canonical constructors. */
  public static class DomainException extends OpenTmfClientResponseException {
    public DomainException(HttpStatusCode statusCode) {
      super(statusCode);
    }

    public DomainException(HttpStatusCode statusCode, String message) {
      super(statusCode, message);
    }

    public DomainException(HttpStatusCode statusCode, String message, String responseBody) {
      super(statusCode, message, responseBody);
    }
  }

  /** A consumer-style subclass missing the three-arg constructor, exercising remap's fallback. */
  public static class NarrowException extends OpenTmfClientResponseException {
    public NarrowException(HttpStatusCode statusCode) {
      super(statusCode);
    }

    public NarrowException(HttpStatusCode statusCode, String message) {
      super(statusCode, message);
    }
  }

  private static HttpHeaders headersWithRetryAfter() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "42");
    headers.set("X-Request-Id", "abc-123");
    return headers;
  }

  @Test
  void detailsAreNull_untilSet() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY);

    assertThat(ex.getHeaders()).isNull();
    assertThat(ex.getRetryAfter()).isNull();
  }

  @Test
  void setResponseDetails_storesHeadersAndRetryAfter() {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);

    ex.setResponseDetails(headersWithRetryAfter(), Duration.ofSeconds(42));

    assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
    assertThat(ex.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
  }

  @Test
  void storedHeadersAreReadOnly() {
    // The live response is closed right after the handler runs; a mutable escape would be a lie.
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    ex.setResponseDetails(headersWithRetryAfter(), null);

    var stored = ex.getHeaders();
    assertThatThrownBy(() -> stored.set("X-Injected", "nope"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void storedHeadersAreDecoupledFromTheSource() {
    var source = headersWithRetryAfter();
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    ex.setResponseDetails(source, null);

    source.set("X-Request-Id", "changed-afterwards");

    assertThat(ex.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
  }

  @Test
  void setResponseDetails_acceptsNulls() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);

    ex.setResponseDetails(null, null);

    assertThat(ex.getHeaders()).isNull();
    assertThat(ex.getRetryAfter()).isNull();
  }

  @Test
  void notFoundSubclass_carriesDetailsToo() {
    var ex = new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND, "gone", "{}");

    ex.setResponseDetails(headersWithRetryAfter(), null);

    assertThat(ex.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
  }

  @Test
  void remap_carriesDetailsToTheDomainType() {
    var source = new OpenTmfClientResponseException(
        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "{}");
    source.setResponseDetails(headersWithRetryAfter(), Duration.ofSeconds(42));

    var remapped = HttpClientUtil.remap(source, DomainException.class);

    assertThat(remapped.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
    assertThat(remapped.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
    assertThat(remapped.getResponseBody()).isEqualTo("{}");
  }

  @Test
  void remap_carriesDetails_evenOnTheTwoArgFallbackPath() {
    var source = new OpenTmfClientResponseException(
        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "{}");
    source.setResponseDetails(headersWithRetryAfter(), Duration.ofSeconds(42));

    var remapped = HttpClientUtil.remap(source, NarrowException.class);

    assertThat(remapped.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
    assertThat(remapped.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
  }

  @Test
  void remap_withoutDetails_staysNull() {
    var source = new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY, "boom", "{}");

    var remapped = HttpClientUtil.remap(source, DomainException.class);

    assertThat(remapped.getHeaders()).isNull();
    assertThat(remapped.getRetryAfter()).isNull();
  }

  @Test
  void survivesSerializationRoundTrip() throws IOException, ClassNotFoundException {
    var ex = new OpenTmfClientResponseException(
        HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "{}");
    ex.setResponseDetails(headersWithRetryAfter(), Duration.ofSeconds(42));

    var bytes = new ByteArrayOutputStream();
    try (var out = new ObjectOutputStream(bytes)) {
      out.writeObject(ex);
    }
    OpenTmfClientResponseException restored;
    try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (OpenTmfClientResponseException) in.readObject();
    }

    assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(restored.getRetryAfter()).isEqualTo(Duration.ofSeconds(42));
    assertThat(restored.getHeaders().getFirst("X-Request-Id")).isEqualTo("abc-123");
  }

  @Test
  void legacyConstructorsRemainReflectivelyResolvable() {
    // HttpClientUtil resolves these by exact signature; new state must never displace them.
    assertThat(HttpClientUtil.createException(HttpStatus.BAD_REQUEST, DomainException.class))
        .isInstanceOf(DomainException.class);
    assertThat(HttpClientUtil.createException(
        HttpStatus.BAD_REQUEST, "msg", DomainException.class))
        .isInstanceOf(DomainException.class);
  }
}
