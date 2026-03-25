package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RequiredArgsConstructor
public class SyncTokenClientImpl {

  private final RestTemplate tokenRestTemplate;
  private final BearerAuthConfig config;

  public ObjectNode getToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    log.debug("Will retrieve a new bearer token (sync) from url: {}, scope: {}, username: {}",
        tokenUrl, formData.get(SCOPE), formData.get(config.getUsernameField()));

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    if (hasText(config.getClientId()) && hasText(config.getClientSecret())) {
      headers.setBasicAuth(config.getClientId(), config.getClientSecret());
    }

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);
    return tokenRestTemplate.postForObject(tokenUrl, request, ObjectNode.class);
  }
}
