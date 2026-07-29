package org.opentmf.client.bearer.util;

import static org.opentmf.client.common.util.TokenUtil.firstNonNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import tools.jackson.databind.node.ObjectNode;

@UtilityClass
public class BearerTokenUtil {

  public static final String SCOPE = "scope";

  public static String findScope(String additionalScopes, String configuredScopes,
      Map<String, String> enricher) {
    var scope = Optional.ofNullable(additionalScopes)
        .map(String::trim)
        .orElse("") + " ";

    scope += Optional.ofNullable(configuredScopes)
        .map(String::trim)
        .orElse("") + " ";

    scope += Optional.ofNullable(enricher.get(SCOPE))
        .map(String::trim)
        .orElse("") + " ";

    return Arrays.stream(scope.trim().split(" "))
        .filter(StringUtils::hasText)
        .collect(Collectors.toCollection(TreeSet::new))
        .stream().collect(Collectors.joining(" "));
  }

  public static String findUsername(BearerAuthConfig config, Map<String, String> formData,
      Map<String, String> enricher) {
    return firstNonNull(
        enricher.get(config.getUsernameField()),
        formData.get(config.getUsernameField())
    );
  }

  /**
   * Builds the token-request form data: configured form data enriched with the caller-provided
   * entries, plus username and scope when present.
   */
  public static MultiValueMap<String, String> enrichFormData(BearerAuthConfig config,
      String username, String scope, Map<String, String> enricher) {
    var map = new HashMap<>(config.getFormData());
    map.putAll(enricher);
    if (StringUtils.hasText(username)) {
      map.put(config.getUsernameField(), username);
    }
    if (StringUtils.hasText(scope)) {
      map.put(SCOPE, scope);
    }
    var linkedMap = new LinkedMultiValueMap<String, String>();
    map.forEach(linkedMap::add);
    return linkedMap;
  }

  public static String extractToken(ObjectNode objectNode, String tokenField) {
    return objectNode.get(tokenField).stringValue();
  }
}
