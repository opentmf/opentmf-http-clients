package org.opentmf.client.bearer.util;

import static org.opentmf.client.common.util.TokenUtil.firstNonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.util.StringUtils;

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
}
