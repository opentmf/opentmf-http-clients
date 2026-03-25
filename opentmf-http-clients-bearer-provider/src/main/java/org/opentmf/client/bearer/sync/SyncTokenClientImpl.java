package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@RequiredArgsConstructor
public class SyncTokenClientImpl {

  private final RestClient restClient;
  private final BearerAuthConfig config;

  public ObjectNode getToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    log.debug("Will retrieve a new bearer token (sync) from url: {}, scope: {}, username: {}",
        tokenUrl, formData.get(SCOPE), formData.get(config.getUsernameField()));

    var requestSpec = restClient.post()
        .uri(tokenUrl)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(formData);

    if (hasText(config.getClientId()) && hasText(config.getClientSecret())) {
      requestSpec.headers(h -> h.setBasicAuth(config.getClientId(), config.getClientSecret()));
    }

    return requestSpec.retrieve().body(ObjectNode.class);
  }
}
