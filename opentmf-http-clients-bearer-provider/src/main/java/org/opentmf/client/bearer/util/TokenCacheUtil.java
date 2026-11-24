package org.opentmf.client.bearer.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;

/**
 * Factory for the production Caffeine token cache with per-entry variable TTL
 * derived from the {@code cacheDuration} of each {@link TokenEntry}.
 */
@Slf4j
public final class TokenCacheUtil {

  @Generated
  private TokenCacheUtil() {
  }

  /**
   * Removes every entry whose token (the {@code tokenField} of its answer) equals {@code token}:
   * an API refused that token as invalid. An entry minted meanwhile holds another token and
   * stays.
   *
   * @return whether an entry was removed
   * @since 3.0.0
   */
  public static boolean dropToken(Cache<String, TokenEntry> cache, String token,
      String tokenField) {
    boolean dropped = cache.asMap().values().removeIf(
        entry -> token.equals(BearerTokenUtil.extractToken(entry.getTokenData(), tokenField)));
    if (dropped) {
      log.info("A cached bearer token was refused as invalid (401, error=\"invalid_token\"): "
          + "its cache entry is dropped, the next token request mints a fresh one");
    }
    return dropped;
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
