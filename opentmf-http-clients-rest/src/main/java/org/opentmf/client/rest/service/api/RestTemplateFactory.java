package org.opentmf.client.rest.service.api;

import org.opentmf.client.common.model.ClientProperties;
import org.springframework.web.client.RestTemplate;

/**
 * Builds a fully configured {@link RestTemplate} for a client.
 *
 * <p>Since 2.6.0: Spring marks {@code RestTemplate} for removal. Use {@link RestClientFactory},
 * whose clients carry the same configuration; this interface keeps working unchanged.</p>
 */
public interface RestTemplateFactory {

  RestTemplate create(String clientId, ClientProperties properties);
}
