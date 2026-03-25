package org.opentmf.client.starter.rest;

import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/**
 * Bridge bean that wraps a Logbook {@link ClientHttpRequestInterceptor}.
 * Injected via {@code ObjectProvider<RestLogbookSupport>} so that factories
 * never reference Logbook types in their constructor signatures, avoiding
 * {@code TypeNotPresentException} when Logbook is absent from the classpath.
 */
public class RestLogbookSupport {

  private final ClientHttpRequestInterceptor interceptor;

  public RestLogbookSupport(ClientHttpRequestInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  public void addInterceptor(RestTemplate restTemplate) {
    restTemplate.getInterceptors().add(interceptor);
  }
}
