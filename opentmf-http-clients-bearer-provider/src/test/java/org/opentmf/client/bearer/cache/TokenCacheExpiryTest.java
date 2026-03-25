package org.opentmf.client.bearer.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.commons.util.JacksonUtil;

class TokenCacheExpiryTest {

  private final AtomicLong fakeTime = new AtomicLong(0);
  private final Ticker fakeTicker = fakeTime::get;

  @Test
  void entry_isEvicted_afterItsTtlExpires() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "tok1")
        .put("expires_in", 2);
    var entry = TokenEntry.from(tokenData, "expires_in", 3600, 1.0);

    cache.put("key1", entry);
    assertThat(cache.getIfPresent("key1")).isNotNull();

    fakeTime.addAndGet(Duration.ofSeconds(3).toNanos());
    cache.cleanUp();
    assertThat(cache.getIfPresent("key1")).isNull();
  }

  @Test
  void entries_withDifferentTtls_evictIndependently() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var shortLived = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "short").put("expires_in", 2),
        "expires_in", 3600, 1.0);
    var longLived = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "long").put("expires_in", 10),
        "expires_in", 3600, 1.0);

    cache.put("short", shortLived);
    cache.put("long", longLived);

    fakeTime.addAndGet(Duration.ofSeconds(3).toNanos());
    cache.cleanUp();

    assertThat(cache.getIfPresent("short")).isNull();
    assertThat(cache.getIfPresent("long")).isNotNull();

    fakeTime.addAndGet(Duration.ofSeconds(8).toNanos());
    cache.cleanUp();

    assertThat(cache.getIfPresent("long")).isNull();
  }

  @Test
  void readDoesNotExtendTtl() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var tokenData = JacksonUtil.getDefaultJsonMapper().createObjectNode()
        .put("access_token", "tok2")
        .put("expires_in", 3);
    var entry = TokenEntry.from(tokenData, "expires_in", 3600, 1.0);

    cache.put("key2", entry);

    fakeTime.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(cache.getIfPresent("key2")).as("should still be present at t=2s").isNotNull();

    fakeTime.addAndGet(Duration.ofSeconds(2).toNanos());
    cache.cleanUp();
    assertThat(cache.getIfPresent("key2")).as("should be evicted at t=4s (past 3s TTL)").isNull();
  }

  @Test
  void cacheKeys_areIsolatedByScope() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var entryA = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "tokenA").put("expires_in", 300),
        "expires_in", 3600, 1.0);
    var entryB = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "tokenB").put("expires_in", 300),
        "expires_in", 3600, 1.0);

    var keyA = org.opentmf.client.common.util.TokenUtil.cacheKey(
        java.net.URI.create("http://auth/token"), "scope_a", null);
    var keyB = org.opentmf.client.common.util.TokenUtil.cacheKey(
        java.net.URI.create("http://auth/token"), "scope_b", null);

    assertThat(keyA).isNotEqualTo(keyB);

    cache.put(keyA, entryA);
    cache.put(keyB, entryB);

    var cachedA = cache.getIfPresent(keyA);
    assertThat(cachedA).isNotNull();
    assertThat(cachedA.getTokenData().get("access_token").stringValue()).isEqualTo("tokenA");
    var cachedB = cache.getIfPresent(keyB);
    assertThat(cachedB).isNotNull();
    assertThat(cachedB.getTokenData().get("access_token").stringValue()).isEqualTo("tokenB");
  }

  @Test
  void cacheKeys_areIsolatedByUsername() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var entryUser1 = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "tok_user1").put("expires_in", 300),
        "expires_in", 3600, 1.0);
    var entryUser2 = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "tok_user2").put("expires_in", 300),
        "expires_in", 3600, 1.0);

    var key1 = org.opentmf.client.common.util.TokenUtil.cacheKey(
        java.net.URI.create("http://auth/token"), "openid", "alice");
    var key2 = org.opentmf.client.common.util.TokenUtil.cacheKey(
        java.net.URI.create("http://auth/token"), "openid", "bob");

    assertThat(key1).isNotEqualTo(key2);

    cache.put(key1, entryUser1);
    cache.put(key2, entryUser2);

    var cached1 = cache.getIfPresent(key1);
    assertThat(cached1).isNotNull();
    assertThat(cached1.getTokenData().get("access_token").stringValue()).isEqualTo("tok_user1");
    var cached2 = cache.getIfPresent(key2);
    assertThat(cached2).isNotNull();
    assertThat(cached2.getTokenData().get("access_token").stringValue()).isEqualTo("tok_user2");
  }

  @Test
  void update_resetsTheTtl() {
    var cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(fakeTicker)
        .build();

    var original = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "orig").put("expires_in", 3),
        "expires_in", 3600, 1.0);
    cache.put("key", original);

    fakeTime.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(cache.getIfPresent("key")).isNotNull();

    var refreshed = TokenEntry.from(
        JacksonUtil.getDefaultJsonMapper().createObjectNode()
            .put("access_token", "refreshed").put("expires_in", 5),
        "expires_in", 3600, 1.0);
    cache.put("key", refreshed);

    fakeTime.addAndGet(Duration.ofSeconds(4).toNanos());
    cache.cleanUp();
    assertThat(cache.getIfPresent("key"))
        .as("should still be present — refreshed entry has 5s TTL from t=2s, now t=6s")
        .isNotNull();

    fakeTime.addAndGet(Duration.ofSeconds(2).toNanos());
    cache.cleanUp();
    assertThat(cache.getIfPresent("key"))
        .as("should be evicted — 5s TTL from t=2s expired at t=7s, now t=8s")
        .isNull();
  }
}
