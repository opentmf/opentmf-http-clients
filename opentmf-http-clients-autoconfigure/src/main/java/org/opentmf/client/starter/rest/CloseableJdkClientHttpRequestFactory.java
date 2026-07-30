package org.opentmf.client.starter.rest;

import java.net.http.HttpClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * A {@link JdkClientHttpRequestFactory} that keeps the underlying {@link HttpClient} reference
 * and implements {@link DisposableBean}, giving JDK-backed clients the same uniform close hook
 * the Apache factory already has ({@code HttpComponentsClientHttpRequestFactory} implements
 * {@code DisposableBean} out of the box).
 *
 * <p>{@code java.net.http.HttpClient} implements {@code AutoCloseable} only since Java 21
 * (JDK-8304165), while this library targets Java 17 — hence the guarded close: on a 21+ runtime
 * it performs a real close (waiting for in-flight requests); on 17 the branch is skipped and the
 * abandoned client is reclaimed by GC, which only pins a selector thread until then.</p>
 */
public class CloseableJdkClientHttpRequestFactory extends JdkClientHttpRequestFactory
    implements DisposableBean {

  private final HttpClient httpClient;

  public CloseableJdkClientHttpRequestFactory(HttpClient httpClient) {
    super(httpClient);
    this.httpClient = httpClient;
  }

  @Override
  public void destroy() throws Exception {
    if (httpClient instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }
}
