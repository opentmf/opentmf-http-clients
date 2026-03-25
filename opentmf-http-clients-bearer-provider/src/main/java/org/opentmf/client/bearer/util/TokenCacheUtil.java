package org.opentmf.client.bearer.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.Generated;
import org.opentmf.client.bearer.model.TokenEntry;

/**
 * Factory for the production Caffeine token cache with per-entry variable TTL
 * derived from {@link TokenEntry#getCacheDuration()}.
 */
public final class TokenCacheUtil {

  @Generated
  private TokenCacheUtil() {
  }

  public static Cache<String, TokenEntry> buildTokenCache() {
    return Caffeine.newBuilder()
        .expireAfter(tokenExpiry())
        .build();
  }

  public static Expiry<String, TokenEntry> tokenExpiry() {
    return new Expiry<>() {
      @Override
      public long expireAfterCreate(String key, TokenEntry entry, long currentTime) {
        return entry.getCacheDuration().toNanos();
      }

      @Override
      public long expireAfterUpdate(String key, TokenEntry entry,
          long currentTime, long currentDuration) {
        return entry.getCacheDuration().toNanos();
      }

      @Override
      public long expireAfterRead(String key, TokenEntry entry,
          long currentTime, long currentDuration) {
        return currentDuration;
      }
    };
  }
}
