package org.opentmf.client.rest.service.api;

import org.opentmf.client.common.model.ClientProperties;
import org.springframework.web.client.RestTemplate;

public interface RestTemplateFactory {

  RestTemplate create(String clientId, ClientProperties properties);
}
