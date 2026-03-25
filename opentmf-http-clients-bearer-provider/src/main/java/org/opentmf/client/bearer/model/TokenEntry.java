package org.opentmf.client.bearer.model;

import tools.jackson.databind.node.ObjectNode;
import java.time.Duration;
import lombok.Value;

@Value
public class TokenEntry {
  ObjectNode tokenData;
  Duration cacheDuration;

  public static TokenEntry from(ObjectNode tokenData, String expiresInField,
      long defaultExpiresInSeconds, double safetyFactor) {
    long expiresIn = tokenData.path(expiresInField).asLong(defaultExpiresInSeconds);
    long cacheSecs = Math.max(1, (long) (expiresIn * safetyFactor));
    return new TokenEntry(tokenData, Duration.ofSeconds(cacheSecs));
  }
}
