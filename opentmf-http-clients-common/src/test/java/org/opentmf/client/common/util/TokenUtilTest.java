package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class TokenUtilTest {

  @Test
  void cacheKey_allParts() {
    String key = TokenUtil.cacheKey(URI.create("https://auth.example.com"), "openid", "admin");
    assertThat(key).isEqualTo("admin openid https://auth.example.com");
  }

  @Test
  void cacheKey_nullUsername() {
    String key = TokenUtil.cacheKey(URI.create("https://auth.example.com"), "openid", null);
    assertThat(key).isEqualTo("null openid https://auth.example.com");
  }

  @Test
  void cacheKey_nullScope() {
    String key = TokenUtil.cacheKey(URI.create("https://auth.example.com"), null, "admin");
    assertThat(key).isEqualTo("admin null https://auth.example.com");
  }

  @Test
  void cacheKey_allNull() {
    String key = TokenUtil.cacheKey(URI.create("https://auth.example.com"), null, null);
    assertThat(key).isEqualTo("null null https://auth.example.com");
  }

  @Test
  void firstNonNull_returnsFirstNonBlank() {
    assertThat(TokenUtil.firstNonNull(null, "", "hello", "world")).isEqualTo("hello");
  }

  @Test
  void firstNonNull_returnsNull_whenAllBlank() {
    assertThat(TokenUtil.firstNonNull(null, "", "  ")).isNull();
  }

  @Test
  void firstNonNull_returnsFirst_whenFirstIsValid() {
    assertThat(TokenUtil.firstNonNull("first", "second")).isEqualTo("first");
  }

  @Test
  void constants() {
    assertThat(TokenUtil.TOKEN_TYPE_BEARER).isEqualTo("Bearer");
    assertThat(TokenUtil.TOKEN_TYPE_BASIC).isEqualTo("Basic");
    assertThat(TokenUtil.CLIENT_PROPERTIES).isEqualTo("ClientProperties");
    assertThat(TokenUtil.WEB_CLIENT).isEqualTo("WebClient");
    assertThat(TokenUtil.REST_TEMPLATE).isEqualTo("RestTemplate");
    assertThat(TokenUtil.TOKEN_SERVICE).isEqualTo("TokenService");
  }
}
