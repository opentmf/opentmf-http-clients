package org.opentmf.client.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.BasicAuthConfig;

class SyncBasicTokenServiceImplTest {

  @Test
  void getTokenType_returnsBasic() {
    var service = new SyncBasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "US-ASCII"));
    assertThat(service.getTokenType()).isEqualTo("Basic");
  }

  @Test
  void getToken_returnsBase64EncodedCredentials() {
    var service = new SyncBasicTokenServiceImpl(
        new BasicAuthConfig("admin", "secret", "US-ASCII"));

    String token = service.getToken();
    String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.US_ASCII);
    assertThat(decoded).isEqualTo("admin:secret");
  }

  @Test
  void getToken_withAdditionalScopes_returnsSameToken() {
    var service = new SyncBasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "US-ASCII"));
    assertThat(service.getToken("openid")).isEqualTo(service.getToken());
  }

  @Test
  void getToken_withUtf8Charset() {
    var service = new SyncBasicTokenServiceImpl(
        new BasicAuthConfig("user", "päss", "UTF-8"));

    String token = service.getToken();
    String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
    assertThat(decoded).isEqualTo("user:päss");
  }

  @Test
  void getToken_withInvalidCharset_fallsBackToAscii() {
    var service = new SyncBasicTokenServiceImpl(
        new BasicAuthConfig("user", "pass", "INVALID_CHARSET"));
    String token = service.getToken();
    assertThat(token).isNotEmpty();
    String decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.US_ASCII);
    assertThat(decoded).isEqualTo("user:pass");
  }
}
