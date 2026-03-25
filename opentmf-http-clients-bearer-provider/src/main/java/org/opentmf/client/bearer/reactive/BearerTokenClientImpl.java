package org.opentmf.client.bearer.reactive;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.exception.BearerWebClientException;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.reactive.util.WebClientUtil;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class BearerTokenClientImpl implements BearerTokenClient {

  private final ClientProperties properties;
  private final BearerAuthConfig bearerConfig;
  private final WebClient webClient;

  @Override
  public Mono<ObjectNode> retrieveToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    log.debug("Will retrieve a new bearer token from url: {}, scope: {}, username: {}",
        tokenUrl, formData.get(SCOPE), formData.get(bearerConfig.getUsernameField()));
    return post(tokenUrl, BodyInserters.fromFormData(formData));
  }

  private Mono<ObjectNode> post(URI url, BodyInserters.FormInserter<String> tokenRequestForm) {
    var clientId = bearerConfig.getClientId();
    var clientSecret = bearerConfig.getClientSecret();

    return webClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .accept(MediaType.APPLICATION_JSON)
        .body(tokenRequestForm)
        .headers(headers -> {
          if (hasText(clientId) && hasText(clientSecret)) {
            headers.setBasicAuth(clientId, clientSecret);
          }
        })
        .retrieve()
        .onStatus(HttpStatusCode::isError,
            clientResponse -> WebClientUtil.handleError(clientResponse, BearerWebClientException.class))
        .bodyToMono(String.class)
        .map(body -> (ObjectNode) JacksonUtil.jsonToTree(body))
        .retryWhen(
            WebClientUtil.retry(properties.getNumRetries(),
                Duration.ofMillis(properties.getRetryWaitMillis())));
  }
}
