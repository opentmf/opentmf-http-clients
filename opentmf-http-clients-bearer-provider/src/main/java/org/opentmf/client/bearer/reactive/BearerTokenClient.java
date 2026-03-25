package org.opentmf.client.bearer.reactive;

import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

public interface BearerTokenClient {

  Mono<ObjectNode> retrieveToken(URI tokenUrl, MultiValueMap<String, String> formData);
}
