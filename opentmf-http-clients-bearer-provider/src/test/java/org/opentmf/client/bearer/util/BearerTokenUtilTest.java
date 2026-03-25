package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BearerAuthConfig;

class BearerTokenUtilTest {

  @Test
  void findScope_mergesAllSources_deduplicatesAndSorts() {
    String result = BearerTokenUtil.findScope("write", "read", Map.of("scope", "admin"));
    assertThat(result).isEqualTo("admin read write");
  }

  @Test
  void findScope_handlesNulls() {
    String result = BearerTokenUtil.findScope(null, null, Map.of());
    assertThat(result).isEmpty();
  }

  @Test
  void findScope_handlesBlankStrings() {
    String result = BearerTokenUtil.findScope("  ", "  ", Map.of("scope", "  "));
    assertThat(result).isEmpty();
  }

  @Test
  void findScope_additionalScopesOnly() {
    String result = BearerTokenUtil.findScope("openid profile", null, Map.of());
    assertThat(result).isEqualTo("openid profile");
  }

  @Test
  void findScope_configuredScopesOnly() {
    String result = BearerTokenUtil.findScope(null, "read write", Map.of());
    assertThat(result).isEqualTo("read write");
  }

  @Test
  void findScope_enricherScopesOnly() {
    String result = BearerTokenUtil.findScope(null, null, Map.of("scope", "admin"));
    assertThat(result).isEqualTo("admin");
  }

  @Test
  void findScope_deduplicates() {
    String result = BearerTokenUtil.findScope("read write", "read admin", Map.of());
    assertThat(result).isEqualTo("admin read write");
  }

  @Test
  void findUsername_fromEnricher() {
    var config = new BearerAuthConfig();
    config.setUsernameField("username");

    String username = BearerTokenUtil.findUsername(
        config,
        Map.of("username", "formUser"),
        Map.of("username", "enricherUser"));
    assertThat(username).isEqualTo("enricherUser");
  }

  @Test
  void findUsername_fromFormData_whenEnricherMissing() {
    var config = new BearerAuthConfig();
    config.setUsernameField("username");

    String username = BearerTokenUtil.findUsername(
        config,
        Map.of("username", "formUser"),
        Map.of());
    assertThat(username).isEqualTo("formUser");
  }

  @Test
  void findUsername_returnsNull_whenAbsentEverywhere() {
    var config = new BearerAuthConfig();
    config.setUsernameField("username");

    String username = BearerTokenUtil.findUsername(config, Map.of(), Map.of());
    assertThat(username).isNull();
  }
}
