package org.opentmf.client.bearer.util;

import static org.opentmf.client.common.util.TokenUtil.firstNonNull;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
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

  /**
   * Reads the token endpoint's success answer, refusing one the token cannot be taken from with
   * a {@link BearerTokenResponseException} that names the endpoint, the status and the token
   * field — never the body.
   *
   * @param tokenUrl   the token endpoint that answered
   * @param status     the success status it answered with
   * @param body       the raw body; {@code null} or blank when it sent none
   * @param tokenField the configured {@code bearer-auth.token-field}
   * @return the answer as a JSON object whose token field is a non-blank string
   */
  public static ObjectNode readTokenResponse(URI tokenUrl, HttpStatusCode status,
      @Nullable String body, String tokenField) {
    if (!StringUtils.hasText(body)) {
      throw new BearerTokenResponseException(tokenUrl, status, tokenField, Reason.EMPTY_BODY);
    }
    JsonNode answer;
    try {
      answer = JacksonUtil.jsonToTree(body);
    } catch (IllegalArgumentException e) {
      // not chained: the parser's message quotes the body
      throw new BearerTokenResponseException(tokenUrl, status, tokenField,
          Reason.NOT_A_JSON_OBJECT);
    }
    if (!(answer instanceof ObjectNode tokenData)) {
      throw new BearerTokenResponseException(tokenUrl, status, tokenField,
          Reason.NOT_A_JSON_OBJECT);
    }
    var token = tokenData.get(tokenField);
    if (token == null || token.isNull()) {
      throw new BearerTokenResponseException(tokenUrl, status, tokenField, Reason.FIELD_MISSING);
    }
    if (!token.isString()) {
      throw new BearerTokenResponseException(tokenUrl, status, tokenField,
          Reason.FIELD_NOT_A_STRING);
    }
    if (!StringUtils.hasText(token.stringValue())) {
      throw new BearerTokenResponseException(tokenUrl, status, tokenField, Reason.FIELD_BLANK);
    }
    return tokenData;
  }

  public static String extractToken(ObjectNode objectNode, String tokenField) {
    return objectNode.get(tokenField).stringValue();
  }
}
