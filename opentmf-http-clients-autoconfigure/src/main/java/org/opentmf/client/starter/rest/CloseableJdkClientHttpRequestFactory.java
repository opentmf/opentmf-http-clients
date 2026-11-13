package org.opentmf.client.starter.rest;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *
 * <p>A companion client built for the same client id (the token calls' own client, when
 * {@code bearer-auth.request-timeout} is set) is closed together with this one.</p>
 */
public class CloseableJdkClientHttpRequestFactory extends JdkClientHttpRequestFactory
    implements DisposableBean {

  private final HttpClient httpClient;
  private final List<HttpClient> companions = new CopyOnWriteArrayList<>();

  public CloseableJdkClientHttpRequestFactory(HttpClient httpClient) {
    super(httpClient);
    this.httpClient = httpClient;
  }

  /** The underlying client, for a request factory that shares its connections. */
  HttpClient httpClient() {
    return httpClient;
  }

  /** Closes the given client together with this factory's own. */
  void closeAlso(HttpClient companion) {
    companions.add(companion);
  }

  @Override
  public void destroy() throws Exception {
    for (var companion : companions) {
      close(companion);
    }
    close(httpClient);
  }

  private static void close(HttpClient client) throws Exception {
    if (client instanceof AutoCloseable closeable) {
      closeable.close();
    }
  }
}
