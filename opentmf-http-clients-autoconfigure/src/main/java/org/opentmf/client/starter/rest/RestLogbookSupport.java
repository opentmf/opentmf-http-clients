package org.opentmf.client.starter.rest;

import java.util.List;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/**
 * Bridge bean that wraps a Logbook {@link ClientHttpRequestInterceptor}, and the one of a client's
 * token calls (whose Logbook never sees the token endpoint's response body).
 * Injected via {@code ObjectProvider<RestLogbookSupport>} so that factories
 * never reference Logbook types in their constructor signatures, avoiding
 * {@code TypeNotPresentException} when Logbook is absent from the classpath.
 */
public class RestLogbookSupport {

  private final ClientHttpRequestInterceptor interceptor;
  private final ClientHttpRequestInterceptor tokenCallInterceptor;

  /** The same interceptor for a client's calls and its token calls. */
  public RestLogbookSupport(ClientHttpRequestInterceptor interceptor) {
    this(interceptor, interceptor);
  }

  /**
   * @param interceptor          for a client's own calls
   * @param tokenCallInterceptor for its token calls
   */
  public RestLogbookSupport(ClientHttpRequestInterceptor interceptor,
      ClientHttpRequestInterceptor tokenCallInterceptor) {
    this.interceptor = interceptor;
    this.tokenCallInterceptor = tokenCallInterceptor;
  }

  /** Appends the client calls' Logbook interceptor to a client's interceptors. */
  public void addInterceptor(List<ClientHttpRequestInterceptor> interceptors) {
    interceptors.add(interceptor);
  }

  /**
   * As {@link #addInterceptor(List)}, on the template's own list.
   *
   * @deprecated since 2.6.0: use {@link #addInterceptor(List)}.
   */
  @Deprecated(since = "2.6.0")
  public void addInterceptor(RestTemplate restTemplate) {
    addInterceptor(restTemplate.getInterceptors());
  }

  /** In a token call's interceptors: the token calls' Logbook interceptor for the client's. */
  public void useTokenCallInterceptor(List<ClientHttpRequestInterceptor> interceptors) {
    interceptors.replaceAll(i -> i == interceptor ? tokenCallInterceptor : i);
  }
}
