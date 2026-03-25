package org.opentmf.client.bearer.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.opentmf.commons.util.JacksonUtil;
import tools.jackson.databind.node.ObjectNode;

class TokenEntryTest {

  @Test
  void from_computesCacheDuration() {
    ObjectNode tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "abc123")
        .put("expires_in", 3600);

    TokenEntry entry = TokenEntry.from(tokenData, "expires_in", 1800, 0.9);

    assertThat(entry.getCacheDuration()).isEqualTo(Duration.ofSeconds(3240));
    assertThat(entry.getTokenData().get("access_token").stringValue()).isEqualTo("abc123");
  }

  @Test
  void from_usesDefaultExpiresIn_whenFieldMissing() {
    ObjectNode tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "abc123");

    TokenEntry entry = TokenEntry.from(tokenData, "expires_in", 1800, 0.9);

    assertThat(entry.getCacheDuration()).isEqualTo(Duration.ofSeconds(1620));
  }

  @Test
  void from_minimumCacheDurationIsOneSecond() {
    ObjectNode tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "abc123")
        .put("expires_in", 0);

    TokenEntry entry = TokenEntry.from(tokenData, "expires_in", 0, 0.9);

    assertThat(entry.getCacheDuration()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void from_customExpiresInField() {
    ObjectNode tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "abc123")
        .put("token_lifetime", 600);

    TokenEntry entry = TokenEntry.from(tokenData, "token_lifetime", 3600, 0.8);

    assertThat(entry.getCacheDuration()).isEqualTo(Duration.ofSeconds(480));
  }

  @Test
  void from_lowSafetyFactor() {
    ObjectNode tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "abc123")
        .put("expires_in", 100);

    TokenEntry entry = TokenEntry.from(tokenData, "expires_in", 3600, 0.1);

    assertThat(entry.getCacheDuration()).isEqualTo(Duration.ofSeconds(10));
  }
}
